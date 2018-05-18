# Persistent Memory for Java

## Background

Persistent memory brings the ability to use byte addressable persistent storage in our programs.
This means using CPU load/store instructions, not block I/O read/write methods, to operate on persistent data.
For background on the new programming model see <https://www.snia.org/PM> or <https://www.usenix.org/system/files/login/articles/login_summer17_07_rudoff.pdf> 

Some building blocks for working with this style of storage are already available.
<https://pmem.io/pmdk/> provides C libraries for using persistent memory operations in your native applications,
whilst <https://developers.redhat.com/blog/2016/12/05/configuring-and-using-persistent-memory-rhel-7-3/> describes how to set up the operating system to run them.

Just one small snag for Java programmers: you can't issue arbitrary CPU instructions directly from Java code.
So to take advantage of this new programming model, you need to call out to C functions. That generally means using JNI.
Two open source Java libraries provide JNI based access to Persistent Memory, with useful high level abstractions layered on top.
These are <https://github.com/pmem/pcj> and <https://mnemonic.apache.org/>
Like the PMDK they build on, both projects originate from Intel.

To see how it works, let's take the use case of an append-only binary log, a key building block of persistence functionality for databases, message queues and such.
The traditional way of constructing one in Java, is to use a `MappedByteBuffer`, obtained by calling `FileChannel.map(...)`.
Persistence is achieved by copying data into the buffer and then calling  `MappedByteBuffer.force()`, which the JDK implements as a native method calling `msync`,
a syscall that flushes the dirty blocks from the O/S cache to the storage device.
This is fairly course-grained and expensive, so many systems will collect a number of writes together in a batch and persist them together with a single `force()`.
That's more efficient overall, though it can raise the latency of individual operations, which have to queue up to join the next batch.

At Red Hat we use this model for persistent message queues <https://github.com/apache/activemq-artemis/tree/master/artemis-journal>
and transaction logs <https://github.com/jbosstm/narayana/tree/master/ArjunaCore/arjuna/classes/com/arjuna/ats/internal/arjuna/objectstore/hornetq>,
used in implementing the JMS and JTA components of the <http://wildfly.org/> Application Server.

Where the filesystem is backed by traditional persistent storage such as SSD or HDD, this block oriented storage approach is the best we can do.
But Persistent Memory gives us some new alteratives. Using C code and a DAX enabled filesystem,
we can use `MAP_SYNC` (<https://lwn.net/Articles/731706/>) to map the file in such a way that we can persist changes by flushing memory directly
from CPU L2 cache to the pmem wihtout going via the storage subsystem or calling into the kernel.
By staying in user space and avoiding the overhead of a syscall, we get much better performance.
But we do still have some overhead, since now we have to make JNI calls to the C library.

So now Java programmers have a choice: Stick with the pure Java approach and take the hit of a syscall, or go with PMDK and suffer the overhead of JNI.
That's Bad or Less Bad, but what we want is Good...

## Making Improvements

What if the JVM itself was able to natively handle Persistent Memory?
Can't we have a smart `MappedByteBuffer` that knows it can implement `force()` as a cheap cache flush when the backing storage is DAX,
but still uses the legacy `msync` syscall for other cases? Then our Java code wouldn't need to be replaced with JNI cruft to take advantage of Persistent Memory.

Turns out that with some tweaking of the JDK source, this is indeed possible. The changes are:

- `FileChannelImpl.map(...)` is overloaded to have a new version with an extra parameter, by which we can request `MAP_SYNC` be used.
If it is, that fact is tracked by a new private `isMapSync` field on the resulting `MappedByteBuffer`.

Why not change FileChannel? Because we have not yet got around to considering the implications for its other subclasses.
On the up side, that would mean we don't have to fiddle around with module exposure to use the new call.

Why change the signature at all - we could just have `map(...)` automatically use `MAP_SYNC` if it's available and fall back to regular mapping if it's not.
We prefer explicit control, but it's debatable.

- `MappedByteBuffer.force()` is modified to consult the `isMapSync` field and replace the `msync` call with a `pmem_persist` when appropriate.
That gives us a lot of speedup, but we can still do a little better. `force0` requires JNI because it makes a syscall.
But because `pmem_persist` is in user space, we can employ `registerNatives()` to remove the JNI overhead entirely from that path.

With these changes, we can run existing pure Java applications against Persistent Memory storage with minimal code changes - just one new parameter to the `FileChannelImpl.map(...)` call -
and without any deployment complexity since we don't need to build/install PMDK or the JNI code to access it.
Best of all, we don't have any runtime overhead.

Well, almost none...

The `MappedByteBuffer` may be large, and with an append-only log use case, we've probably only written a small part at the end.
So, we'd prefer to signal that we want to persist only that modified range, so that we're not issuing unnecessary cache operations for the entire buffer.
To do this we need one more API change:

- `MappedByteBuffer.force()` is overloaded to have a `MappedByteBuffer.force(int from, int to)` variant.
The `msync` call actually already works this way, it's just not been exposed to Java because the O/S storage block cache will generally do the right thing anyhow, ignoring blocks that are not dirty.
But the `pmem_persist` approach is so much more efficient that the overhead of using an unnecessarily large range becomes much more noticeable.

## Show me the numbers

Let's take a simple micro-benchmark that appends a few million records to a log.

In the worst case, we can call out to PMDK's `libpmemlog` via JNI.
That's the slowest approach to using Persistent Memory from Java.
The custom JDK native solution should be somewhat faster.

For reference, we'll also implement an equivalent executable in C code, calling `libpmemlog` directly, removing the JVM from the picture entirely.

For execution against regular DIMMs on a legacy CPU (i.e. without the new cache optimization instructions), the timings are:

| Code          | Time (seconds) |
| ------------- |:-------------:|
| Java with patched JDK      | 8.1      |
| C native binary | 8.8 |
| Java with JNI | 10.0 |

Wow! That doesn't look quite right. We've gone from noticeable overhead to actually outperforming C code.
The JVM's JIT must be going a really good job, though using a simpler algorithm for the log management helps too.
The most important point though, is that we're now running a lot more efficiently than we can with JNI.

## But wait, there is more!

There is one more thing we can do to streamline this. Because the `pmem_persist` call essentially just issues a set of CPU cache management instructions,
we can teach the JIT to replace it with generated code, using a compiler intrinsic. That allows the JDK runtime to be even more aggressive in its optimizations.
Let's see how that approach stacks up. 

| Code          | Time (seconds) |
| ------------- |:-------------:|
| Patched JDK (compiler intrinsic)      | 6.0      |
| Patched JDK (method call)      | 8.1      |
| C native binary | 8.8 |
| Java with JNI | 10.0 |

Oh yeah. Now that's Good.



## Try it yourself.

You can try out the prototype yourself. It needs a bit of setup though. These instructions assume Fedora x86_64.
YMMV in other environments - it will be cross-platform eventually, but not yet. 

- Get a JDK11 source tree. hg clone from <http://openjdk.java.net/> or try <https://builds.shipilev.net/workspaces/jdk-jdk.tar.xz>

- Configure and build the unaltered JDK to make sure it's working.

```
bash configure --with-debug-level=release --with-native-debug-symbols=external --disable-warnings-as-errors
make
```

- Now, patch the JDK to add the Persistent Memory functionality and rebuild.

```
patch -p1 < /path/to/pmem.patch
make
```

- Build the benchmark against the new JDK. The `PmemLogJDKImpl` code won't compile against a regular JDK, since it uses the new API.

```
export JAVA_HOME=/path/to/jdk/build/linux-x86_64-normal-server-release/jdk
export PATH=$JAVA_HOME/bin:$PATH
javac --add-exports java.base/sun.nio.ch=ALL-UNNAMED *.java
```

- Build the JNI code too, so you can run the `PMemLogJNI` version of the benchmark:

```
sudo dnf install libpmemlog
javac -h . PmemLogJNIImpl.java
g++ -I $JAVA_HOME/include/ -I $JAVA_HOME/include/linux -fPIC -l pmemlog --shared -o libpmemlogjni.so pmemlogjni.cpp
```

- You're going to need some Persistment Memory! If you don't have any, you can fake it:

See the 'Experimenting without NVDIMMs' section of <https://developers.redhat.com/blog/2016/12/05/configuring-and-using-persistent-memory-rhel-7-3/>
for how to configure the kernel to memmap a chunk of RAM to simulate pmem.
The demo assumes at least 1GB, but you can change the source to work with less.

Then, as root:

```
dnf install ndctl
ndctl create-namespace  -f -e namespace0.0 -m memory -M mem
mkfs.xfs -f /dev/pmem0
mount -o dax /dev/pmem0 /mnt/pmem/
mkdir /mnt/pmem/test
chown -R you:you /mnt/pmem/test/
```

- All done. Run run it with

`java Driver`

Note that the JIT compiler intrinsic is enabled by default. To run without it

`export NO_FORCE_MAPSYNC_INTRINSIC=1`

You can change the `Driver.java` source to use the different implementations for comparison.

You may need to delete the log file between runs, because hey it's persistent and will fill up!

- For the C driver, do

```
gcc -l pmemlog driver.c
time ./a.out
```


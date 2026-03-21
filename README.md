# 🚀 NitroDB

**Ultra-fast, Zero-GC, In-Memory/Disk Key-Value Store built on Modern Java.**

NitroDB is an experimental, high-performance Key-Value database built entirely from scratch to explore the absolute limits of **Java 21**. By combining the **Bitcask** storage model with **Virtual Threads (Project Loom)** and the **Foreign Function & Memory API (Project Panama)**, NitroDB achieves massive concurrency and sub-millisecond latencies without the usual JVM Garbage Collection pauses.

Oh, and it speaks the **RESP (Redis) protocol**, so you can connect to it right now using `redis-cli`! No custom clients required.

No Netty. No huge dependency trees. Just pure, modern Java pushing hardware to its limits.

## ✨ Key Features

*   **100k+ Concurrent Connections:** Powered by Project Loom. Every client gets its own Virtual Thread. It's simple blocking I/O code that performs like complex async NIO.
*   **Zero-GC Off-Heap Cache:** Uses Panama's `MemorySegment` and `Arena` APIs to store gigabytes of cached data directly in native memory. 
*   **Smart LRU Eviction:** Implements the exact same *Randomized LRU* algorithm used by Redis (O(1) cost per eviction using random sampling).
*   **Blazing Fast Storage (Bitcask):** 
    *   Append-only writes for maximum disk throughput.
    *   Lock-free positional reads (`FileChannel.read()`) meaning unlimited concurrent readers with zero thread contention.
*   **Redis Compatible:** Full support for RESP2. Use standard Redis clients or `redis-cli` to interact with NitroDB.
*   **Fast Recovery:** Background compaction generates "Hint Files" so node restarts take milliseconds, not minutes.
*   **Embedded or Standalone:** Run it as a standalone server or embed it directly into your Java application as a library.

## 👍 Architecture Highlights

NitroDB isn't just another HashMap wrapper. It uses a serious storage engine:

1. **The Write Path:** Every `PUT` is appended sequentially to the active data file (LogEntry). Once written, the in-memory `KeyDir` (a `ConcurrentHashMap`) is updated with a `RecordPointer` (File ID, Offset, Size). 
2. **The Read Path:** A `GET` hits the Off-Heap cache first. If it misses, it looks up the offset in the `KeyDir`, and performs a single lock-free positional read from the disk.
3. **Background Compaction:** An asynchronous engine constantly monitors dead bytes, safely merging immutable files and purging tombstones without blocking active reads/writes.

## ✋ Quick Start

### Prerequisites
* Java 21 or higher (must support `--enable-preview`)
* Maven

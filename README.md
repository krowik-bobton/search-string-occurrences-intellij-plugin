<!-- Plugin description -->

# Search String Occurrences Plugin
IntelliJ-based plugin that searches for specific text occurrences inside a chosen directory.
Designed to be efficient and responsive.

<!-- Plugin description end -->

## Features
- **Responsiveness**: allows user to cancel searching in any moment.
- **Concurrency**: search engine is based on producer-consumer pattern with light-weight Kotlin Coroutines.
- **Binary Files Filter**: automatically filters binary files. 
- **Linux Optimized**: Automatically avoids virtual filesystems (`/proc`, `/sys`, `/dev`, `/run`)

## How to Install
1. Download the latest release ZIP 
2. Open your JetBrains IDE (e.g. **IntelliJ IDEA**, **PyCharm**)
3. Go to **File → Settings → Plugins**  
4. Click the **⚙️ (gear)** icon and select **Install Plugin from Disk...**  
5. Choose the downloaded `.zip` file  
6. Click **Apply** and restart your IDE
7. After restart, a new **tool window** will appear in your IDE sidebar

## Requirements
- IntelliJ IDEA 2023.3 or newer
- Java 17+

## Technology used
- **Language**: **Kotlin** (combined with Java.NIO)
- **Concurrency**
  - **Kotlin Coroutines**: Can efficiently process many files at once.
  - **Dispatchers**: Coroutines work on `Dispatchers.IO` for efficient I/O-bound file processing.
  - **Kotlin Flow & Channels**: Implemented **producer-consumer** pattern using suspending collections.
- **Testing**
  - **JUnit 5**: Unit tests helping with future updates.
- **UI** 
  - **Swing**: UI components are implemented using Swing.
- **Build System**
  - **Gradle**: standard for IntelliJ plugins.


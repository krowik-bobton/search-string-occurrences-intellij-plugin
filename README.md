<!-- Plugin description -->

# Search String Occurrences

IntelliJ-based plugin that searches for specific text occurrences inside chosen directory

## Features
- Searches for a given string recursively through files in a directory
- Avoids virtual filesystems (`/proc`, `/sys`, `/dev`, `/run`)
- Streams results as a Kotlin Flow of occurrences
- Allows user to cancel seraching in any moment

## How to Install

1. Open the `/releases` directory of this repository  
2. Download the latest release ZIP (e.g. `search-string-occurrences-intellij-plugin-0.0.1.zip`)  
3. Open your JetBrains IDE (e.g. **IntelliJ IDEA**, **PyCharm**)
4. Go to **File → Settings → Plugins**  
5. Click the **⚙️ (gear)** icon and select **Install Plugin from Disk...**  
6. Choose the downloaded `.zip` file  
7. Click **Apply** and restart your IDE
8. After restart, a new **tool window** will appear in your IDE sidebar

## Requirements
- IntelliJ IDEA 2023.3 or newer
- Kotlin 1.9+
- Gradle 8+
- Java 17+
<!-- Plugin description end -->

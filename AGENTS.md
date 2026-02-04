# CherryDiskInfo - AI Agent Documentation

## Project Overview

**CherryDiskInfo** is an Android storage health monitoring application inspired by CrystalDiskInfo. It provides transparency into the lifespan of soldered storage (eMMC/UFS) on mobile devices that cannot be easily replaced.

### Project Purpose
PC users have CrystalDiskInfo to monitor SSD lifespan, but mobile/tablet users facing soldered eMMC/UFS storage remain in the dark. The market is flooded with devices nearing storage end-of-life, and Android only introduced official APIs in version 15, leaving older devices as complete black boxes.

### Key Features (Planned)
- Storage device health monitoring (eMMC/UFS/NVMe)
- SMART-like attribute reading
- Root-based detailed information access
- Fallback estimation methods for non-rooted devices

### Current Status
🚧 Early development stage - core architecture established, data source implementations pending.

---

## Technology Stack

### Core Technologies
| Component | Technology |
|-----------|------------|
| Platform | Android (API 29 - 36) |
| Language | Kotlin 2.0.21 |
| Build System | Gradle 8.13.2 |
| UI Framework | Jetpack Compose |
| Architecture | MVVM (Model-View-ViewModel) |

### Key Dependencies
- **AndroidX Core KTX**: Core Android extensions
- **Jetpack Compose**: Modern declarative UI toolkit
  - BOM: 2024.09.00
  - Material3: Material Design 3 components
  - Navigation: Screen navigation
- **Lifecycle**: ViewModel and lifecycle-aware components
- **Coroutines**: Asynchronous programming (used in Repository pattern)

---

## Project Structure

```
CherryDiskinfo/
├── app/                                  # Main application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/cherrydiskinfo/
│   │   │   │   ├── MainActivity.kt       # Application entry point
│   │   │   │   ├── data/                 # Data layer
│   │   │   │   │   ├── datasource/       # Data sources
│   │   │   │   │   │   └── StorageDataSource.kt    # Storage data source implementations
│   │   │   │   │   ├── model/            # Data models
│   │   │   │   │   │   ├── RootStatus.kt           # Root permission status enum
│   │   │   │   │   │   └── StorageInfo.kt          # Storage information data class
│   │   │   │   │   └── repository/       # Repository pattern
│   │   │   │   │       └── StorageRepository.kt    # Storage data repository
│   │   │   │   ├── ui/                   # UI layer
│   │   │   │   │   ├── screens/          # Screen composables
│   │   │   │   │   │   └── MainScreen.kt           # Main screen UI
│   │   │   │   │   └── theme/            # Theme definitions
│   │   │   │   │       ├── Color.kt                # Color palette
│   │   │   │   │       ├── Theme.kt                # Light/dark theme
│   │   │   │   │       └── Type.kt                 # Typography
│   │   │   │   └── viewmodel/            # ViewModels
│   │   │   │       └── StorageViewModel.kt         # Storage info ViewModel
│   │   │   ├── res/                      # Android resources
│   │   │   │   ├── values/               # Values (strings, colors, themes)
│   │   │   │   ├── drawable/             # Drawable resources
│   │   │   │   └── xml/                  # XML configurations
│   │   │   └── AndroidManifest.xml       # App manifest
│   │   ├── test/                         # Unit tests
│   │   └── androidTest/                  # Instrumented tests
│   ├── build.gradle.kts                  # Module build configuration
│   └── proguard-rules.pro                # ProGuard rules
├── gradle/
│   └── libs.versions.toml                # Version catalog
├── build.gradle.kts                      # Root build configuration
├── settings.gradle.kts                   # Project settings
└── gradle.properties                     # Gradle properties
```

---

## Architecture

### MVVM Architecture Pattern

The project follows the **MVVM (Model-View-ViewModel)** architecture pattern with a repository layer:

```
┌─────────────────┐
│   UI Layer      │  Composables (MainScreen.kt)
│  (Compose)      │  ↕
└────────┬────────┘
         │ StateFlow
┌────────▼────────┐
│  ViewModel      │  StorageViewModel
│   Layer         │  ↕
└────────┬────────┘
         │
┌────────▼────────┐
│   Repository    │  StorageRepository
│    Layer        │  ↕
└────────┬────────┘
         │
┌────────▼────────┐
│  Data Source    │  Root/Adb/SystemApi/Estimated
│    Layer        │  DataSource implementations
└─────────────────┘
```

### Data Source Priority
The `StorageRepository` attempts to fetch data from multiple sources in priority order:

1. **RootStorageDataSource** (Highest priority) - Requires root access, provides complete SMART-like info
2. **SystemApiStorageDataSource** - Android 15+ StorageHealthStats API
3. **AdbStorageDataSource** - Requires ADB debugging authorization
4. **EstimatedStorageDataSource** (Fallback) - Always available, least accurate

### Key Components

#### Data Models (`data/model/`)
- `StorageInfo`: Core storage device information (type, health, capacity, etc.)
- `RootStatus`: Root permission status (GRANTED/DENIED/UNKNOWN)
- `SmartAttribute`: SMART-like attribute structure
- `StorageInfoResult`: Sealed class for operation results (Success/Error/Loading)
- `DataSourceType`/`DataSourceInfo`: Data source metadata

#### Repository (`data/repository/`)
- `StorageRepository`: Coordinates data sources, exposes clean API to ViewModel

#### ViewModel (`viewmodel/`)
- `StorageViewModel`: Manages UI state using StateFlow, exposes data to UI layer

#### UI (`ui/`)
- `MainScreen.kt`: Main screen with storage info display, health indicator, data source list
- Theme: Cherry red color scheme inspired by CrystalDiskInfo

---

## Build Commands

### Build
```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build all variants
./gradlew build
```

### Install
```bash
# Install debug version on connected device
./gradlew installDebug
```

### Clean
```bash
./gradlew clean
```

### Check
```bash
# Run all checks (lint, test)
./gradlew check
```

---

## Testing

### Test Structure
```
app/src/
├── test/                    # Unit tests (JVM)
│   └── java/com/example/cherrydiskinfo/
│       └── ExampleUnitTest.kt
└── androidTest/             # Instrumented tests (Android device/emulator)
    └── java/com/example/cherrydiskinfo/
        └── ExampleInstrumentedTest.kt
```

### Running Tests
```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

### Current Test Status
- Only example/placeholder tests exist
- Real unit tests for Repository and ViewModel need to be implemented
- Integration tests for data sources need to be added

---

## Code Style Guidelines

### Kotlin Style
- Follows **Kotlin Official Code Style** (configured in `gradle.properties`)
- Use meaningful Chinese comments for business logic (project convention)
- Use English for class/function names following standard conventions

### Naming Conventions
- **Classes**: PascalCase (e.g., `StorageRepository`, `MainActivity`)
- **Functions**: camelCase (e.g., `getStorageInfo()`, `checkRootStatus()`)
- **Variables**: camelCase (e.g., `storageInfo`, `rootStatus`)
- **Constants**: UPPER_SNAKE_CASE

### Compose UI Guidelines
- Composable functions start with capital letters (PascalCase)
- Private composables for internal screen components
- Use `Modifier` as first optional parameter
- Follow Material3 design patterns

### Comments
- Classes and public functions have KDoc documentation in Chinese
- Complex logic is explained with inline comments in Chinese
- TODO markers for unimplemented features

---

## Configuration Details

### SDK Versions
- **Compile SDK**: 36
- **Min SDK**: 29 (Android 10)
- **Target SDK**: 36

### Permissions
```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

### ProGuard
- Minification is **disabled** for release builds (`isMinifyEnabled = false`)
- ProGuard rules file exists at `app/proguard-rules.pro` but is mostly empty

---

## Development Notes

### TODO Items
The following features are marked as TODO in the codebase:

1. **RootStorageDataSource**: Implement reading from `/sys/class/mmc_host/` and EXT_CSD parsing
2. **AdbStorageDataSource**: Implement `dumpsys diskstats` parsing
3. **SystemApiStorageDataSource**: Implement Android 15+ StorageHealthStats API integration
4. **EstimatedStorageDataSource**: Implement StatFs-based estimation logic
5. **Tests**: Add comprehensive unit and integration tests

### Chinese Language Usage
This project uses Chinese for:
- User-facing UI strings
- Code comments and documentation
- Commit messages (assumed)

English is used for:
- Code identifiers (classes, functions, variables)
- File names
- Build configuration

---

## Security Considerations

### Root Access
- App attempts to detect and use root access for detailed storage info
- Root check uses `su -c id` command execution
- Root is **optional** - app works without it (with reduced functionality)

### Storage Permissions
- Only requests `READ_EXTERNAL_STORAGE` permission
- Does not write to external storage
- No network permissions declared

### Data Privacy
- All storage data is read locally
- No data transmission to external servers
- Backup rules configured in `data_extraction_rules.xml` and `backup_rules.xml`

---

## Dependencies Management

Version catalog is managed in `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.13.2"
kotlin = "2.0.21"
composeBom = "2024.09.00"
```

To add new dependencies:
1. Add version to `[versions]` section
2. Add library to `[libraries]` section
3. Reference in `app/build.gradle.kts` using `libs.xxx.xxx`

---

## IDE Configuration

The project includes `.idea/` directory with:
- Code style settings
- Inspection profiles
- Run configurations

Recommended IDE: **Android Studio** (latest stable version)

---

## References

- [CrystalDiskInfo](https://crystalmark.info/en/software/crystaldiskinfo/) - The inspiration for this project
- [Android StorageHealthStats](https://developer.android.com/reference/android/app/usage/StorageStatsManager) - Android 15+ API
- [JEDEC eMMC Standard](https://www.jedec.org/) - eMMC specifications

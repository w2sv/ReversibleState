# ReversibleState

![Maven Central Version](https://img.shields.io/maven-central/v/io.github.w2sv/reversible-state)
![GitHub release (latest by date including pre-releases)](https://img.shields.io/github/v/release/w2sv/ReversibleState?include_prereleases)
[![Build](https://github.com/w2sv/ReversibleState/actions/workflows/workflow.yaml/badge.svg)](https://github.com/w2sv/ReversibleState/actions/workflows/workflow.yaml)
![GitHub](https://img.shields.io/github/license/w2sv/ReversibleState)

Reversible state holders for Kotlin Multiplatform.

`ReversibleState` tracks an editable value alongside an applied value, exposes whether there are uncommitted changes, and provides commit/revert operations for pushing or discarding edits.

## Targets

- JVM
- iOS Arm64
- iOS Simulator Arm64

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.w2sv:reversible-state:<version>")
}
```

For Kotlin Multiplatform projects:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.w2sv:reversible-state:<version>")
        }
    }
}
```

## Usage

```kotlin
val appliedState = MutableStateFlow("saved")

val state = ReversibleStateFlow(
    scope = scope,
    appliedState = appliedState,
    commitState = { appliedState.value = it }
)

state.value = "draft"

check(state.isDirty.value)

suspend fun save() {
    state.commit()
}

state.revert()
```

Use `ReversibleStateComposition` to group several reversible states and commit or revert only the dirty children.

## License

Apache License 2.0. See [LICENSE](LICENSE).

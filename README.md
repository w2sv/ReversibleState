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
import com.w2sv.reversiblestate.ReversibleStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

suspend fun editExample(scope: CoroutineScope) {
    val appliedState = MutableStateFlow("Saved")
    val state = ReversibleStateFlow(
        scope = scope,
        appliedState = appliedState,
        commitState = { commit -> appliedState.value = commit.current }
    )

    state.value = "Draft"
    state.update { "$it updated" }
    val hasPendingChanges = state.isDirty.value // true
    val committed = state.commit() // true; called from a suspending function

    state.value = "Another draft"
    state.revert() // Restores "Draft updated".
}
```

`ReversibleStateFlow` implements `StateFlow<T>` for collection and synchronous reads, with editing through writable
`value` and atomic `update()`. `isDirty.value` immediately compares the draft with the trusted persisted baseline.
`commit()` serializes callers and returns `true` after a new successful write or `false` when no write is needed.
Edits and revert remain responsive during persistence. Revert restores the trusted baseline, even if the applied state
emission for a successful commit is delayed.

`StateCommit(previous, current)` contains the trusted baseline and draft captured for a write. Successful commits emit
this transition on `commits`, a replay-free `SharedFlow` for transient notifications. Inactive or slow collectors may
miss events, so keep critical side effects out of this flow. Keep `commitState` focused on persistence: if it writes and
then fails, the library may retry the write.

Before the first local persistence attempt, the default `AppliedStateSyncPolicy.WhenUnmodified` follows applied state
changes only when no newer local editing intent exists. `Always` copies every emission into the draft; `Never` leaves
the draft alone. After a persistence attempt, delayed emissions cannot be reliably distinguished from newer external
writes, so they cannot change the trusted baseline and `WhenUnmodified` stops automatic synchronization. Recreate the
editor from a freshly loaded applied state when resynchronization is needed. `previous` may differ from the exact value
overwritten by a concurrent external writer.

`ReversibleStateComposition` groups several states. Its aggregate `isDirty.value` is synchronous. It serializes
composition commits, returns whether any child wrote, and calls `onCommit` only with children whose `commit()` returned
`true`. Earlier children remain persisted if a later child fails; their writes are not one atomic transaction.

## License

Apache License 2.0. See [LICENSE](LICENSE).

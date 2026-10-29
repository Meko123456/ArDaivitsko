# Ar Daivitsko

**A reminder that keeps asking until you answer.**

Ar Daivitsko (არ დაივიწყო) is Georgian for "don't forget". A normal reminder fires once: if you're
in a meeting when it goes off, it's gone, and the credit card bill gets paid a day late, with a
fine. Here, a task on your calendar nudges you every few hours until you tap **Done**, **In
progress** or **Not today**, right on the notification. A task you can't miss gets louder as its
deadline nears, ending in a full-screen alert like an alarm clock. Nothing unanswered disappears:
it carries over to the next day, at the top.

## Two native apps

| | Android | iOS |
| --- | --- | --- |
| Folder | [`android/`](android) | [`ios/`](ios) |
| Language | Kotlin, Jetpack Compose | Swift, SwiftUI |

There's no shared code: each app is built the way its platform does things best. What they share
is [docs/behaviour.md](docs/behaviour.md), which says exactly when a task nudges and what each
answer does, with worked examples that both apps' tests check, so a task behaves the same on
either phone.

## Status

Under construction: both apps are being built now. Each folder's README says how to build and
test it.

## License

MIT, see [LICENSE](LICENSE).

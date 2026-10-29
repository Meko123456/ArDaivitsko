#if canImport(AlarmKit)
import AlarmKit

/// What a Can't-miss alarm carries: the occurrence it's for.
@available(iOS 26.0, *)
struct DeadlineAlarmMetadata: AlarmMetadata {
    var occurrenceID: String
}
#endif

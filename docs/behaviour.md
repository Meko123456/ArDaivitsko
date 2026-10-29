# How Ar Daivitsko behaves

Ar Daivitsko is two native apps, one in Kotlin for Android and one in Swift for iOS, with no
shared code. This document is the one place the behaviour is written down, so a task nags you the
same way on either phone. Each app's tests check the worked examples at the end.

A normal reminder fires once: if you're in a meeting at 11:00, it's gone, and the credit card
bill is paid a day late with a fine. Ar Daivitsko keeps asking until you answer.

## Tasks

A task has:
- a **title**, and an optional note
- the **day** it's for
- a **deadline** on that day (default 23:59)
- **Can't miss**, on or off
- **repeat**: never, daily, weekly (same weekday) or monthly (same day of the month)
- the time **nudging starts** (default 10:00)
- **how often** it nudges: every 1, 2, 3 or 4 hours (default 3)

Each occurrence of a task is in one state:
- **Open**: not answered yet.
- **In progress**: started, not finished.
- **Done**: finished; its nudges stop.
- **Moved**: "Not today" gave it another day; it now belongs to that day.

Settings: **quiet hours** (default 22:00 to 08:00), and the defaults for new tasks.

## Nudges

All times are wall-clock times in the phone's time zone: 10:00 stays 10:00 across time zones and
daylight-saving changes. A time that doesn't exist on a day (the hour skipped in spring) moves
forward by the gap (02:30 becomes 03:30); a time that happens twice (in autumn) is the first. The
In progress and Snooze cadences follow the wall clock too: six hours from 13:05 is 19:05 on the
clock, and an hour after 01:30 EDT on the night the clocks go back is 02:30 EST. So do "the final
three hours" and "the last hour" before a deadline; slots that don't exist that night fold
forward into the ones that do.

**Regular nudges.** On the occurrence's day, while it's Open, nudges come at the start time and
then every interval: with 10:00 and 3 hours, at 10:00, 13:00, 16:00, 19:00 and 22:00. None come
before the moment the task was created (one added at 15:20 nudges first at 16:00).

**Quiet hours** silence regular, In progress, snoozed and overdue nudges (above, 22:00 is skipped).
They never silence a Can't-miss task's extras: the heads-up, the final three hours, the alarm and
Deadline passed.

**In progress** nudges half as often: from the moment it's marked, every twice the interval.
Tapping In progress again starts that cadence again from the new tap.

**Snooze 1 hour**: the next nudge comes an hour after the tap, and the cadence carries on from
there (snoozed at 13:02 with 3 hours: 14:02, 17:02, 20:02). A snooze that runs past midnight gives
its one nudge after midnight (unless quiet hours silence it), and the new day keeps its own grid
from the start time. Inside a Can't-miss task's final three hours, a snooze skips the :00 and :30
nudges before it, and its own nudge counts as one of the
final three hours' (quiet hours don't silence it). It never moves or drops the alarm or Deadline
passed.

**Can't miss** tasks get more:
- **A heads-up the evening before**, at 19:00: "Tomorrow is the last day".
- **The final three hours**: a nudge on every hour and half hour from three hours before the
  deadline until it, quiet hours or not. These replace the regular nudges in that window.
- **An alarm**: the first of those within the last hour, and at or after the moment the task was
  created, is a full-screen alert, like an alarm clock (Android: a full-screen notification; iOS
  26: AlarmKit; earlier iOS: a time-sensitive notification).
- **At the deadline**, if still not Done: "Deadline passed". If the deadline comes before the day
  ends, regular nudges carry on until the day ends, then the task is overdue.
- In progress doesn't stop any of these; only Done does.

A task that isn't Can't miss shows its deadline in its notifications' text; its regular nudges carry
on until the day ends, deadline or not, and then it's overdue.

**Overdue.** An occurrence still Open or In progress after its day carries over: it's shown at
the top of today as "Overdue since 28 Oct", and nudges every day from the start time on the
regular grid (every interval if Open, twice the interval if In progress), until it's answered.
The Can't-miss extras don't repeat after the deadline. A repeating task's unanswered yesterday and
its today both nudge, as separate notifications grouped together.

**Missed nudges.** If the phone was off, or the app couldn't run, at a nudge time, one catch-up
nudge comes as soon as it can (at start-up, or when the app opens): one per unanswered occurrence,
with the text of its latest missed nudge, not one for each missed time.

**Two at once.** When one occurrence has two nudges at the same instant, only the strongest comes:
Deadline passed, then the alarm, then the final three hours', then the heads-up, then the rest.

## Answers

From the notification, without opening the app:
- **Done**: stops the nudges. A repeating task's next occurrence is already on its own day.
- **In progress**: nudges half as often (above).
- **Not today**: moves the occurrence to tomorrow, same deadline time, and says so with an
  Undo. On a Can't-miss task's deadline day, the app opens and asks first ("This is the last
  day. Move it anyway?"). An overdue occurrence moves to the day after today. The move clears In
  progress: on its new day it nudges like an Open one.
- **Snooze 1 hour**.

A nudge that comes before the task's own day (the evening-before heads-up) offers only Done and In
progress.

iOS shows all four on the notification. Android shows three buttons (Done, In progress, Not today);
tapping the notification opens the task, with Snooze 1 hour and "Move to…" (pick a day).

## Repeats

Each repeat makes its own occurrence on its own day, whatever happened to the last one; an overdue
occurrence stays until it's answered. Monthly repeats keep the day of the month, or the month's last
day when it's shorter: from the 31st, 31 Jan, 28 Feb (29 in a leap year), 31 Mar, 30 Apr, 31 May.

## Reliability

Nudges are scheduled for their exact times and survive the app being closed, the phone
restarting, the clock or time zone changing, and an app update. The app schedules everything again
whenever it opens, as a safety net.

## Screens

- **Today**: overdue first, then today's tasks, each with its state and next nudge.
- **Calendar**: a month grid with a mark on days that have tasks (filled while anything is open,
  hollow when all done); a day shows its tasks.
- **Add / edit a task**: the fields above.
- **Settings**: quiet hours, defaults, and what the phone allows (notifications, exact alarms,
  full-screen alerts), with a way to fix each.
- **A Home Screen widget**: today's open tasks, overdue first, each with a Done button.

## Worked examples

Times are in Asia/Tbilisi (UTC+4, no daylight saving). Defaults unless stated: start 10:00, every
3 hours, quiet hours 22:00–08:00, deadline 23:59.

**1. The credit card bill.** "Pay the credit card bill", day 28 Oct 2026, Can't miss, created 27 Oct
12:00, never answered:
- 27 Oct 19:00, heads-up
- 28 Oct 10:00, 13:00, 16:00, 19:00, regular
- 28 Oct 21:00, 21:30, 22:00, 22:30, final three hours (from 20:59)
- 28 Oct 23:00, the alarm (the first slot within the last hour, from 22:59)
- 28 Oct 23:30, final three hours
- 28 Oct 23:59, deadline passed
- 29 Oct 10:00, 13:00, 16:00, 19:00, overdue; the same every later day

**2. In progress.** "Call the bank", day 29 Oct, not Can't miss, created 28 Oct. Marked In progress at
29 Oct 13:05:
- 29 Oct 10:00, 13:00, then 19:05 (01:05 is past the day, in quiet hours)
- 30 Oct 10:00, 16:00, overdue and in progress (22:00 is quiet)

**3. Snooze.** "Pay rent", day 1 Nov, created 31 Oct. Snoozed at 1 Nov 13:02:
- 1 Nov 10:00, 13:00, then 14:02, 17:02, 20:02 (23:02 is quiet)

**4. Added late.** "Buy medicine", day 29 Oct, created 29 Oct 15:20:
- 29 Oct 16:00, 19:00

**5. Not today.** "Renew the parking permit", day 29 Oct, Not today at 29 Oct 11:00:
- 29 Oct 10:00, then it moves; 30 Oct 10:00, 13:00, 16:00, 19:00

**6. Monthly from the 31st.** Occurrences on 31 Jan 2027, 28 Feb 2027, 31 Mar 2027, 30 Apr 2027,
31 May 2027.

**7. Daylight saving.** In America/New_York, day 8 Mar 2026 (clocks go from 02:00 to 03:00), start
02:30, every 3 hours, no quiet hours, created 7 Mar: 03:30, 05:30, 08:30, 11:30, 14:30, 17:30, 20:30,
23:30.

**8. Snooze near the deadline.** As example 1, unanswered until Snooze at 28 Oct 22:40: 23:00, the
alarm; 23:40, the snooze's nudge; 23:59, deadline passed. The 23:30 nudge is skipped.

**9. Added at the last minute.** "Pay the credit card bill", day 28 Oct, Can't miss, created 28 Oct
23:10: 23:30, the alarm (the first slot within the last hour after it was created); 23:59, deadline
passed.

**10. A deadline on the night the clocks go forward.** In America/New_York, day 8 Mar 2026, Can't
miss, deadline 04:00, created 7 Mar, no quiet hours: the final three hours from 01:00 give 01:00,
01:30, 03:00 (the alarm; 02:00 and 02:30 don't exist and fold into 03:00 and 03:30), 03:30, then
04:00, deadline passed.

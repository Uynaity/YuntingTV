# Android TV performance audit

## Goal

Audit the Android TV radio application for code paths that can materially harm
startup time, UI responsiveness, playback stability, memory use, network load,
or background CPU/battery use on low-end TV hardware.

## Requirements

- Review the application source, manifest, build configuration, and relevant
  project specifications without changing production code.
- Cover startup/lifecycle, Compose rendering and focus handling, ViewModel
  state/coroutines, network/data mapping, image loading, Media3 playback, and
  persistent/background work.
- Rank findings by user-visible severity and likelihood, not by stylistic
  preference.
- For each finding, provide code evidence, the triggering scenario, expected
  impact, and a feasible remediation.
- Clearly separate confirmed static-analysis findings from risks that require
  profiling on representative hardware.

## Acceptance Criteria

- [x] All production Kotlin files and relevant app/build configuration are inspected.
- [x] Findings are ordered by severity and include precise file/line references.
- [x] Each finding explains why the code is costly on low-end Android TV devices.
- [x] Each finding has a practical, scoped remediation and suggested validation.
- [x] Existing automated checks or a compile/build check are run where feasible.
- [x] The final report states residual profiling/test gaps.

## Notes

- Keep `prd.md` focused on requirements, constraints, and acceptance criteria.
- Lightweight tasks can remain PRD-only.
- For complex tasks, add `design.md` for technical design and `implement.md` for execution planning before `task.py start`.

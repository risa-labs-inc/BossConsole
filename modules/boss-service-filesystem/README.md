# Filesystem service boundaries

Operations use held native directory handles rather than re-resolving a checked path.
This is an application boundary, not a same-user OS sandbox.

## Watching and renaming

Recursive watches retain authorized descendant handles until the collector closes.
On Linux and macOS, registrations follow renamed directories and update their visible
paths; renaming a descendant rebinds the watch to its new location. Renaming the
watched root itself ends the stream by normal completion: the root is the path the
caller named, so a new name requires a new registration. On Windows, NTFS can refuse
a parent directory rename while descendant
notification handles are open. Stop the affected watch before renaming and reconnect
it afterward; a refused rename does not stop the existing watch or modify files.
Cancellation closes the handles and permits the rename again.

This follows Microsoft's [directory rename rules](https://learn.microsoft.com/en-us/windows-hardware/drivers/ddi/ntifs/ns-ntifs-_file_rename_information#remarks).
The cross-platform regression checks both live-watching rename behavior on POSIX and
Windows refusal, continued delivery, and rename after cancellation.

## Resource and mutation outcomes

All service instances share at most eight watch streams and 128 held watch
directories (up to 8 MiB of Windows notification buffers). These are process
limits, not a reserved share per stream. A tree exceeding available capacity fails
with RESOURCE_EXHAUSTED; callers must rescan and reconnect after freeing capacity.

Recursive deletion reports failures rather than claiming success after partial
removal. Some entries may already have been removed when a later operation fails.

Windows replacement retains the original destination under `.boss-replace-*` if
rollback cannot restore its name. Preserve that entry for recovery. If deleting
that backup fails after the source move commits, the reported error names
`Remove committed replacement backup`: the new destination is already installed,
so inspect both paths before retrying the rename.

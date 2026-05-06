# 2026-05-06 test runners

Recent runs all used the shared runner:

```powershell
node tools/adb-mid-exposure-check.mjs
```

## Saved recipes

| Script | Source log | Device | Browser | Start | MID |
| --- | --- | --- | --- | --- | --- |
| `run-yanggalbi-s7-001.ps1` | `s7-yanggalbi-mid-exposure-200-20260506-002155.log` | SM-G930S | Chrome | 1 | `82095489871` |
| `run-yanggalbi-s7-resume-013.ps1` | `s7-yanggalbi-mid-exposure-resume-20260506-003437.log` | SM-G930S | Chrome | 13 | `82095489871` |
| `run-kellsen-s10-resume-069.ps1` | `s10-kellsen-mid-exposure-resume-20260506-004135.log` | SM-G977N | Samsung Internet | 69 | `87327803739` |

## Usage

Run from repository root:

```powershell
.\test\0505\run-yanggalbi-s7-001.ps1
.\test\0505\run-yanggalbi-s7-resume-013.ps1
.\test\0505\run-kellsen-s10-resume-069.ps1
```

Each script writes timestamped `.log` and `.err.log` files into `test/0505`.

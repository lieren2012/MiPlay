# Phase 0 configuration

Copy the example files to working names before filling them in:

```powershell
Copy-Item config/vehicle.lacrosse-2025.example.json config/vehicle.lacrosse-2025.json
Copy-Item config/phone.primary.example.json config/phone.primary.json
Copy-Item config/license-boundary.example.json config/license-boundary.json
```

Then edit the working files and run:

```powershell
python scripts/check_phase0.py
```

The working files may contain personal device information and should normally remain outside Git. Add them to `.gitignore` after copying if they contain identifiers or captured notes.

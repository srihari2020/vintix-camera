# Keep source line numbers useful in release crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Settings enum names are persisted in DataStore; keep them stable across R8 runs.
-keep enum com.srihari.vintix.settings.ExportResolutionPreset { *; }
-keep enum com.srihari.vintix.settings.ExportQualityPreset { *; }

# CameraX/Compose/Coil publish their own consumer rules. Keep only app-specific
# reflective surfaces here so release builds stay small.

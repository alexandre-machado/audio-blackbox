# R8 rules for the release build (issue #416). Debug builds are unaffected; this file only
# applies when isMinifyEnabled is true.
#
# Policy: keep only what is reached reflectively or by name at runtime, and name that caller in a
# comment next to the rule. Never add a package-wide keep of cc.machado.audioblackbox.**: it
# silently disables shrinking/obfuscation for the whole app and is exactly what Play Console's
# "DEX code optimization" vital penalizes.
#
# Audit (issue #416), and why each of these needs NO app-side keep rule:
# - Manifest components (AudioBlackboxApplication, MainActivity, RecorderService, BootReceiver,
#   RecordingWidgetProvider) and the androidx FileProvider: AGP/aapt2 generates keep rules for
#   every class named in the merged manifest and in res/ XML.
# - RecordingWidgetRenderer's RemoteViews.setInt(R.id.widget_annunciator, "setColorFilter", ...):
#   the method name is resolved on the framework's android.widget.ImageView inside the launcher
#   process, never on an app class, so nothing of ours is looked up by name.
# - QualityPreset persistence (RetentionWindowPreferences writes preset.name, QualityPreset.from
#   matches it back via entries.firstOrNull { it.name == stored }): Enum.name() returns the
#   string literal passed to the enum constructor, which R8 preserves even when it renames or
#   unboxes the enum. No Enum.valueOf / Class.forName reflection exists in the app.
# - ViewModels are built through explicit ViewModelProvider.Factory instances (Dashboard, Gallery,
#   Settings), not reflective no-arg construction.
# - DataStore Preferences, kotlinx.coroutines, Compose and lifecycle ship their own consumer
#   rules. The app has no WorkManager dependency, and Compose ui-tooling is debugImplementation
#   only.

# Caller: ExportErrorLogger records up to 15 stack frames of every export/crash exception into the
# on-device error log that users share from the Settings diagnostics export. Keeping line numbers
# (and a constant source-file name, so real file names are not leaked) lets those traces, and
# Play Console's crash reports, be retraced with this build's mapping.txt. This keeps attributes
# only; it does not keep or un-obfuscate any class.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

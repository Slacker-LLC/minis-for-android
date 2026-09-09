from pathlib import Path

p = Path("src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
s = p.read_text(encoding="utf-8")
old = '''        val nativeVision = currentModel?.let {
            it.inputModalities?.map { m -> m.lowercase() }?.contains("image") == true
        } == true
'''
if s.count(old) != 1:
    raise SystemExit(f"visionPlaceholder nativeVision anchor count={s.count(old)}")
s = s.replace(old, "        val nativeVision = currentModel?.hasImageInput == true\n", 1)
p.write_text(s, encoding="utf-8")

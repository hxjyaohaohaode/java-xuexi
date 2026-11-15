# Synthetic media fixtures

These small files were generated for the private-asset integration tests with the local
`ffmpeg` `lavfi` sources. They contain no downloaded media, speech, people or private data.
They test parsing, content-type validation, byte hashes and authenticated delivery; they
are not evidence that TypeForge implements audio/video generation or transcoding.

| File | Generated input | Observed encoded file |
| --- | --- | --- |
| `tone.mp3` | 440 Hz sine wave, 0.25-second input, libmp3lame at 64 kbit/s | MP3, mono, 44.1 kHz, 64 kbit/s; frame duration 0.287347 seconds including encoder padding |
| `clip.mp4` | Steel-blue 160×90 color source at 12 fps for 0.5 seconds; 660 Hz sine audio | H.264/yuv420p video, AAC mono 44.1 kHz audio, fast-start MP4; container/audio/video duration 0.5 seconds |

The complete original command line was not retained, so this note records the generation
parameters and actual `ffprobe` observations without claiming a byte-for-byte rebuild recipe.

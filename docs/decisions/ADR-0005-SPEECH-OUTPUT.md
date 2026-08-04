# ADR-0005: Speech output

## Status

Accepted for output and playback semantics. Application Default Credentials
are the approved direction but are not yet implemented.

## Context

A validated companion comment may be printed and spoken. Speech can take
longer than journal processing and must not block observation transport. A
successful synthesis response does not prove that the user heard the comment.

The current Google client is implemented with an API key from adjacent
`authentication.json`, not Application Default Credentials.

## Decision

Use Google Cloud Text-to-Speech after a validated `COMMENT`.

Console and speech are output channels coordinated after model validation.
Speech synthesis and local playback do not use `ObservationBus`, and generated
comments are not republished as external observations.

Speech delivery succeeds only after audio playback completes. Successful
synthesis alone is not delivery. Only successfully delivered output enters
short-term previous-comment history.

Use Application Default Credentials as the target Google authentication
mechanism. Credential migration is separate implementation work and must not
be documented as complete until code and tests change.

Keep one active playback path. Microphone input, speech recognition, automatic
interruption, and barge-in remain deferred.

## Consequences

- `SILENT` causes no synthesis or playback.
- Speech work is isolated from the observation-bus thread.
- Console and speech outcomes can be recorded separately.
- Playback/synthesis failure does not redeliver journal observations or repeat
  the LLM decision.
- The current API-key implementation is a visible gap recorded in
  `CURRENT_STATE.md`.

## Rejected alternatives

- Publishing comments or audio requests through `ObservationBus`.
- Treating synthesis completion as successful delivery.
- Overlapping playback of multiple comments.
- Storing raw audio in turn traces.
- Adding microphone, recognition, voice cloning, or streaming TTS now.

## Relevant implementation references

- [`CommentSink.java`](../../src/main/java/kairon/output/CommentSink.java)
- [`SpeechGateway.java`](../../src/main/java/kairon/output/SpeechGateway.java)
- [`GoogleCloudTextToSpeechClient.java`](../../src/main/java/kairon/speech/GoogleCloudTextToSpeechClient.java)
- [`JavaSoundAudioPlayer.java`](../../src/main/java/kairon/speech/JavaSoundAudioPlayer.java)
- [`SpeechOutputTest.java`](../../src/test/java/kairon/observer/SpeechOutputTest.java)
- [`CURRENT_STATE.md`](../CURRENT_STATE.md)

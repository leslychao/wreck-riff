# Wreck Riff

Follow docs/MVP_SPEC.md and docs/DECISIONS.md. One Java 21 Gradle project,
jMonkeyEngine 3.8.1-stable, Minie 9.0.3, one manual fixed-step PhysicsSpace.
Do not introduce another physics owner, framework, server, or external asset dependency.
Use the Microsoft JDK 21 explicitly; the machine's default Java is 17.

Run test, physicsTest and verifyAssets. A graphical smoke test must create a real
window; absence of a display/controller/audio device is not a passing result.
Preserve original procedural assets and their generators and provenance.
Never declare FEEL_APPROVED or MVP_ACCEPTED without the owner's actual review.


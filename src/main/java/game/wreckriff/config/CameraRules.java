package game.wreckriff.config;

public record CameraRules(float distance, float height, float lookHeight, float lookAhead,
        float fov, float turboFov, float positionResponse, float rotationResponse,
        float sweepRadius, float wallMargin, float shakeAngle, float shakeDistance) {
    public CameraRules {
        if (distance<=0 || height<=0 || fov<30 || fov>100 || turboFov<fov || sweepRadius<=0
                || positionResponse<=0 || rotationResponse<=0) throw new IllegalArgumentException("Invalid camera tuning");
    }
    public static CameraRules load() { return Configs.load("camera",CameraRules.class); }
}

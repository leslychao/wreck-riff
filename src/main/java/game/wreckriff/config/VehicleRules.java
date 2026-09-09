package game.wreckriff.config;

public record VehicleRules(float mass, float gravity, float width, float length, float height,
        float wheelRadius, float wheelBase, float suspensionRestLength, float suspensionStiffness,
        float suspensionCompression, float suspensionDamping, float maxSuspensionForce,
        float frictionSlip, float handbrakeFriction, float rollInfluence,
        float engineForce, float brakeForce, float maxSpeed, float turboSpeed, float reverseSpeed,
        float lowSpeedSteering, float highSpeedSteering, float steeringResponse,
        float handbrakeTorque, float handbrakeYawDamping, float stabilizingTorque, float gripReturnSeconds,
        float turboDrain, float turboRegen, float turboRegenDelay, float recoveryCost,
        float recoveryHold, float recoveryCooldown, float recoveryProtection,
        float carPairRestitution, float impactStabilizerOffSeconds, float impactStabilizerReturnSeconds) {
    public VehicleRules {
        if (mass<=0 || gravity<=0 || wheelRadius<=0 || maxSpeed<=0 || turboSpeed<maxSpeed
                || width<=0 || length<=0 || height<=0 || wheelBase<=0 || wheelBase>=length
                || reverseSpeed<=0 || reverseSpeed>maxSpeed || suspensionStiffness<=0
                || suspensionCompression<0 || suspensionDamping<0 || handbrakeFriction<=0
                || handbrakeFriction>frictionSlip || rollInfluence<0 || rollInfluence>1
                || lowSpeedSteering<=0 || lowSpeedSteering>=90 || highSpeedSteering<=0 || highSpeedSteering>lowSpeedSteering
                || steeringResponse<=0 || handbrakeTorque<0 || stabilizingTorque<0
                || turboDrain<=0 || turboRegen<0 || turboRegenDelay<0 || recoveryCost<=0 || recoveryHold<=0
                || suspensionRestLength<=0 || maxSuspensionForce<=mass*gravity/4
                || engineForce<=0 || brakeForce<=0 || frictionSlip<=0 || gripReturnSeconds<=0
                || handbrakeYawDamping<0 || recoveryCooldown<0 || recoveryProtection<0
                || !Float.isFinite(carPairRestitution) || carPairRestitution<0 || carPairRestitution>1
                || !Float.isFinite(impactStabilizerOffSeconds) || impactStabilizerOffSeconds<=0
                || !Float.isFinite(impactStabilizerReturnSeconds) || impactStabilizerReturnSeconds<=0) throw new IllegalArgumentException("Invalid vehicle tuning");
    }
    public static VehicleRules load() { return Configs.load("vehicle",VehicleRules.class); }
}

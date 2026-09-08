package game.wreckriff.config;

public record VehicleRules(float mass, float gravity, float width, float length, float height,
        float wheelRadius, float wheelBase, float suspensionRestLength, float suspensionStiffness,
        float suspensionCompression, float suspensionDamping, float maxSuspensionForce,
        float frictionSlip, float handbrakeFriction, float rollInfluence,
        float engineForce, float brakeForce, float maxSpeed, float turboSpeed, float reverseSpeed,
        float lowSpeedSteering, float highSpeedSteering, float steeringResponse,
        float handbrakeTorque, float stabilizingTorque, float gripReturnSeconds,
        float turboDrain, float turboRegen, float turboRegenDelay, float recoveryCost,
        float recoveryHold, float recoveryCooldown, float recoveryProtection) {
    public VehicleRules {
        if (mass<=0 || gravity<=0 || wheelRadius<=0 || maxSpeed<=0 || turboSpeed<maxSpeed
                || suspensionRestLength<=0 || maxSuspensionForce<=mass*gravity/4
                || engineForce<=0 || brakeForce<=0 || frictionSlip<=0 || gripReturnSeconds<=0
                || recoveryCooldown<0 || recoveryProtection<0) throw new IllegalArgumentException("Invalid vehicle tuning");
    }
    public static VehicleRules load() { return Configs.load("vehicle",VehicleRules.class); }
}

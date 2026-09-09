package game.wreckriff.ai;

import game.wreckriff.config.Configs;

public record AiRules(int schemaVersion, int decisionTicks, int targetChangeTicks,
        int memoryTicks, float sightRange, float sightHalfAngleDegrees,
        int reactionMinTicks, int reactionMaxTicks, float repairThreshold,
        float repairReleaseThreshold, float maximumPickupPath, float turnPenalty,
        float activeHazardPenalty, float minimumLookAhead, float maximumLookAhead,
        float cruiseSpeed, float attackDistance, float steeringGain, int stuckCheckTicks,
        float stuckMinimumProgress, int reverseTicks, int recoveryAfterTicks,
        float machineGunRange, float machineGunAngleDegrees, float powerAngleDegrees,
        float passDistance, float passSideOffset, float passForwardDistance, int passHoldTicks,float turboSeekThreshold) {
    public AiRules {
        if (schemaVersion!=1 || decisionTicks!=12 || targetChangeTicks<decisionTicks || memoryTicks<=0
                || sightRange<=0 || sightHalfAngleDegrees<=0 || sightHalfAngleDegrees>180
                || reactionMinTicks<=0 || reactionMaxTicks<reactionMinTicks
                || repairThreshold<=0 || repairThreshold>=repairReleaseThreshold || repairReleaseThreshold>1
                || maximumPickupPath<=0 || turnPenalty<0 || activeHazardPenalty<0
                || minimumLookAhead<=0 || maximumLookAhead<minimumLookAhead || cruiseSpeed<=0
                || attackDistance<=0 || steeringGain<=0 || stuckCheckTicks<=0 || stuckMinimumProgress<=0
                || reverseTicks<=0 || recoveryAfterTicks<stuckCheckTicks*2 || machineGunRange<=0
                || machineGunAngleDegrees<=0 || powerAngleDegrees<=0 || passDistance<=0 || passSideOffset<=0
                || passForwardDistance<=0 || passHoldTicks<=0 || turboSeekThreshold<=0 || turboSeekThreshold>=100)
            throw new IllegalArgumentException("Invalid AI rules");
    }
    public static AiRules load() { return Configs.load("ai",AiRules.class); }
}

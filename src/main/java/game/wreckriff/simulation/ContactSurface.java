package game.wreckriff.simulation;

/** Authored surface identity; unknown geometry is never guessed from an object name. */
public enum ContactSurface {
    UNKNOWN, METAL, GLASS, RUBBER, CONCRETE, ASPHALT, EARTH, WOOD;

    public static ContactSurface fromMaterial(String material) {
        if(material==null)return UNKNOWN;
        return switch(material) {
            case "steel","rust","blue","black","yellow","red","cyan","ivory","purple","faded-red" -> METAL;
            case "glass","light-white" -> GLASS;
            case "rubber" -> RUBBER;
            case "concrete","brick","stone","dark-concrete","cast-concrete","park-paving","district-slate","district-warm","district-service","district-fair" -> CONCRETE;
            case "asphalt","road-surface","road-wet","road-patch","road-marking" -> ASPHALT;
            case "earth","district-earth","gravel","road-shoulder","grass","park-leaf","park-ground","district-garden" -> EARTH;
            case "wood" -> WOOD;
            default -> UNKNOWN;
        };
    }
}

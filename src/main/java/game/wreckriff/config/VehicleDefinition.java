package game.wreckriff.config;

/** The three always-unlocked player chassis; bosses retain their own profiles. */
public enum VehicleDefinition {
    RIVET("rivet","Rivet",800,"Отбойник","Направленный импульс: урон и отталкивание. Контроль дистанции."),
    GRINDER("grinder","Дробила",1040,"Мясорубка","Передние валы удерживают и перемалывают цель. Во время захвата можно стрелять."),
    SPARK("spark","Искра",640,"Отскок","Боковой рывок оставляет шашку. Атака с фланга и быстрый выход из боя.");

    private final String id,displayName,specialName,description;
    private final float maximumHp;
    VehicleDefinition(String id,String displayName,float maximumHp,String specialName,String description) {
        this.id=id;this.displayName=displayName;this.maximumHp=maximumHp;
        this.specialName=specialName;this.description=description;
    }
    public String id() { return id; }
    public String displayName() { return displayName; }
    public float maximumHp() { return maximumHp; }
    public String specialName() { return specialName; }
    public String description() { return description; }
    public VehicleProfile profile(VehicleRules rules) { return VehicleProfile.player(id,rules); }
    public static VehicleDefinition forId(String id) {
        for(var definition:values())if(definition.id.equals(id))return definition;
        throw new IllegalArgumentException("Unknown player vehicle: "+id);
    }
}

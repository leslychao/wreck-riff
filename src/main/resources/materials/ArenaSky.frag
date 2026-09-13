#import "Common/ShaderLib/GLSLCompat.glsllib"
uniform vec4 m_HorizonColor, m_ZenithColor, m_CloudColor, m_SunColor;
uniform vec3 m_SunDirection;
uniform float m_CloudAmount;
varying vec3 direction;

float hash(vec2 p) {return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float noise(vec2 p) {
    vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);
    return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);
}
void main() {
    vec3 d=normalize(direction);
    float elevation=max(0.0,d.y);
    vec3 color=mix(m_HorizonColor.rgb,m_ZenithColor.rgb,smoothstep(0.0,.72,elevation));
    // Fixed world directions prevent clouds crawling as the car or camera moves.
    vec2 p=d.xz/max(.18,elevation+.27)*2.8;
    float weather=noise(p)*.58+noise(p*2.03+vec2(8.7,2.3))*.28+noise(p*4.09-vec2(3.1,6.2))*.14;
    float cloud=smoothstep(.65-m_CloudAmount*.45,.88-m_CloudAmount*.33,weather);
    cloud*=smoothstep(-.10,.12,d.y);
    color=mix(color,m_CloudColor.rgb*(.65+.35*weather),cloud*m_CloudAmount);
    float glow=pow(max(0.0,dot(d,m_SunDirection)),96.0);
    color+=m_SunColor.rgb*glow*(1.0-cloud*.85)*.28;
    gl_FragColor=vec4(color,1.0);
}

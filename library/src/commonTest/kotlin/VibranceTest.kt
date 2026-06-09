import dev.romainguy.vibrance.Vibrance
import kotlin.test.Test

class VibranceTest {
    @Test
    fun pigmentsMixing() {
        //>>> pg.mix([0.50841904, 0.258023, 0.00149352, 0.2320644399])
        //array([0.13748928, 0.51603482, 0.87044384])
        val color = Vibrance().mix(0.50841904f, 0.258023f, 0.00149352f, 0.23206444f)
        println(color.joinToString(", "))
    }

    @Test
    fun lerp() {
        //>>> pg.lerp([0.0, 0.0, 1.0], [0.0, 1.0, 1.0], 0.5)
        //array([-0.04225142,  0.47493777,  0.9970628 ])
        val color = Vibrance().lerp(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.5f)
        println(color.joinToString(", "))
    }
}

import dev.romainguy.vibrance.Vibrance
import kotlin.test.Test

class VibranceTest {
    @Test
    fun test() {
        //>>> pg.mix([0.50841904, 0.258023, 0.00149352, 0.2320644399])
        //array([0.13748928, 0.51603482, 0.87044384])
        val color = Vibrance().mix(0.50841904f, 0.258023f, 0.00149352f, 0.23206444f)
        println(color.joinToString(", "))
    }
}
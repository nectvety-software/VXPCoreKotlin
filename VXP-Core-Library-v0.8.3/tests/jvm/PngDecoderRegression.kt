import vxpcore.PngDecoder
import java.util.Base64

// 1x1 opaque red PNG.
fun main() {
    val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"
    )
    val image = requireNotNull(PngDecoder.decode(png))
    require(image.width == 1 && image.height == 1)
    require(image.pixels.size == 1)
    println("[OK] pure Kotlin PNG decode: ${image.width}x${image.height} rgb565=0x${(image.pixels[0].toInt() and 0xffff).toString(16)}")
}

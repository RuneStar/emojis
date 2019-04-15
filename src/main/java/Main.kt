import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun main() {
    val shortCodes = shortCodes()
    downloadSvgs(shortCodes.values.distinct(), Path.of("svg"))
    names(shortCodes, Path.of("svg"), Path.of("names.csv"))
    val size = 16
    rasterize(Path.of("svg"), Path.of("png"), size)
    combine(Path.of("png"), Path.of("emojis.png"), size)
    roundAlpha(Path.of("emojis.png"))
    optimize(Path.of("emojis.png"))
}

private fun shortCodes(): Map<String, String> {
    return jacksonObjectMapper()
        .readValue<Map<String, String>>(URL("https://api.github.com/emojis"))
        .filterValues { it.contains("/unicode/") }
        .mapValues { it.value.substringBeforeLast('.').substringAfterLast("/") }
}

private fun downloadSvgs(codePoints: Iterable<String>, dir: Path) {
    Files.createDirectories(dir)
    for (codePoint in codePoints) {
        val svgUrl = "http://twemoji.maxcdn.com/2/svg/$codePoint.svg"
        try {
            URL(svgUrl).openStream().use { Files.copy(it, dir.resolve("$codePoint.svg")) }
        } catch (e: IOException) {
            println(svgUrl)
        }
    }
}

private fun rasterize(inputDir: Path, outputDir: Path, size: Int) {
    Files.createDirectories(outputDir)
    val p = ProcessBuilder("inkscape/inkscape", "--shell").inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE).start()
    inputDir.toFile().listFiles().forEach { inputFile ->
        val outputFile = outputDir.resolve(inputFile.nameWithoutExtension + ".png")
        p.outputStream.write("-f $inputFile -e $outputFile -w $size -h $size\n".replace('\\', '/').toByteArray())
    }
    p.outputStream.write("quit\n".toByteArray())
    p.outputStream.flush()
    p.waitFor()
}

private fun names(shortCodes: Map<String, String>, dir: Path, output: Path) {
    val names = StringBuilder()
    dir.toFile().listFiles().sorted().map { it.nameWithoutExtension }.forEachIndexed { i, codePoint ->
        shortCodes.filter { it.value == codePoint }.forEach { names.append("${it.key},$i\n") }
    }
    Files.writeString(output, names.toString())
}

private fun combine(dir: Path, output: Path, size: Int) {
    val files = dir.toFile().listFiles().sorted()
    val dst = BufferedImage(size, files.size * size, BufferedImage.TYPE_INT_ARGB)
    files.forEachIndexed { i, file ->
        dst.createGraphics().drawImage(ImageIO.read(file), 0, i * size, null)
    }
    ImageIO.write(dst, "png", output.toFile())
}

private fun roundAlpha(file: Path) {
    val img = ImageIO.read(file.toFile())
    for (x in 0 until img.width) {
        for (y in 0 until img.height) {
            val argb = img.getRGB(x, y)
            val alpha = argb ushr 24
            val rgb = argb and 0xFFFFFF
            val newAlpha = if (alpha > 100) 255 else 0
            img.setRGB(x, y, (newAlpha shl 24) or rgb)
        }
    }
    ImageIO.write(img, "png", file.toFile())
}

private fun optimize(file: Path) {
    ProcessBuilder(
        "pingo",
        "-pngpalette=100",
        "-nodithering",
        "-s9",
        file.toString()
    ).inheritIO().start().waitFor()
}
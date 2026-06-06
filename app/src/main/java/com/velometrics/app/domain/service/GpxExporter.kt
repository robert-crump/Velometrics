package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.util.PolylineDecoder
import java.io.OutputStream
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpxExporter @Inject constructor() {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    fun export(edges: List<MapEdge>, routeName: String, outputStream: OutputStream) {
        val gpx = toGpxString(edges, routeName)
        outputStream.write(gpx.toByteArray(Charsets.UTF_8))
    }

    fun toGpxString(edges: List<MapEdge>, routeName: String): String {
        val writer = StringWriter()
        val timestamp = isoFormatter.format(Instant.now())

        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.append("<gpx version=\"1.1\" creator=\"Velometrics\"")
        writer.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        writer.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        writer.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        writer.append("  <metadata>\n")
        writer.append("    <name>${escapeXml(routeName)}</name>\n")
        writer.append("    <time>$timestamp</time>\n")
        writer.append("  </metadata>\n")
        writer.append("  <trk>\n")
        writer.append("    <name>${escapeXml(routeName)}</name>\n")
        writer.append("    <trkseg>\n")

        for (edge in edges) {
            val points = PolylineDecoder.decode(edge.geometryEncoded)
            for (point in points) {
                writer.append("      <trkpt lat=\"${point.latitude}\" lon=\"${point.longitude}\"/>\n")
            }
        }

        writer.append("    </trkseg>\n")
        writer.append("  </trk>\n")
        writer.append("</gpx>\n")

        return writer.toString()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

package com.rollandostudio.universaltvcast

import com.rollandostudio.universaltvcast.lan.Lan
import com.rollandostudio.universaltvcast.lan.LanDevice
import com.rollandostudio.universaltvcast.lan.LanEntry
import com.rollandostudio.universaltvcast.lan.MediaServers
import com.rollandostudio.universaltvcast.lan.Smb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanTest {
    @Test fun ordreNaturel() {
        val names = listOf("Épisode 10", "Épisode 2", "Épisode 1").sortedWith { a, b -> Lan.natural(a, b) }
        assertEquals(listOf("Épisode 1", "Épisode 2", "Épisode 10"), names)
    }

    @Test fun adresses() {
        assertEquals("192.168.1.40" to -1, Lan.parseAddress("192.168.1.40"))
        assertEquals("192.168.1.40" to 4450, Lan.parseAddress("192.168.1.40:4450/Films"))
        assertEquals("PC-SALON" to -1, Lan.parseAddress("\\\\PC-SALON\\Films"))
        assertEquals("Films", Lan.parseShare("\\\\PC-SALON\\Films"))
        assertEquals("Vidéos", Lan.parseShare("smb://nas/Vidéos/Séries"))
        assertNull(Lan.parseShare("192.168.1.40"))
    }

    @Test fun genres() {
        assertEquals(LanEntry.Kind.VIDEO, Lan.kindOf(null, "Dune.mkv"))
        assertEquals(LanEntry.Kind.PHOTO, Lan.kindOf(null, "IMG_0001.JPG"))
        assertEquals(LanEntry.Kind.OTHER, Lan.kindOf(null, "notes.txt"))
        assertEquals("application/octet-stream", Lan.mimeOf("notes.txt"))
    }

    @Test fun dossierDlna() {
        val d = LanDevice(LanDevice.Kind.MEDIA_SERVER, "NAS", "10.0.0.2")
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="12" parentID="0"><dc:title>Films</dc:title></container>
            <item id="13" parentID="0"><dc:title>Dune</dc:title><upnp:class>object.item.videoItem</upnp:class>
              <res protocolInfo="http-get:*:image/jpeg:*">http://10.0.0.2/t.jpg</res>
              <res protocolInfo="http-get:*:video/x-matroska:*" size="1000" duration="2:35:10.000">http://10.0.0.2/dune.mkv</res></item>
            </DIDL-Lite>"""
        val e = MediaServers.parseDidl(didl, d)
        assertEquals(2, e.size)
        assertEquals("12", e[0].ref)
        assertEquals("http://10.0.0.2/dune.mkv", e[1].ref)
        assertEquals(LanEntry.Kind.VIDEO, e[1].kind)
        assertEquals(9310.0, e[1].duration!!, 0.1)
    }

    @Test fun nomNetbios() {
        val b = ByteArray(57 + 2 * 18)
        b[56] = 2
        "WORKGROUP      ".toByteArray().copyInto(b, 57); b[57 + 15] = 0; b[57 + 16] = 0x80.toByte()
        "PC-SALON       ".toByteArray().copyInto(b, 75); b[75 + 15] = 0x20
        assertEquals("PC-SALON", Smb.parseNodeStatus(b))
    }
}

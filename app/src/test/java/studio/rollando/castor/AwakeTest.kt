package studio.rollando.castor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.rollando.castor.dlna.Dlna

/** Télé en veille : ses annonces d'autres services ne doivent pas la faire passer pour allumée. */
class AwakeTest {
    @Test fun veilleNestPasAllumee() {
        // Relevé le 27/11/2026 sur la Philips 55OLED760 éteinte.
        assertFalse(Dlna.isRendererMessage("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-tpv-org:service:PhotoFrame:1\r\nNTS: ssdp:alive\r\nUSN: uuid:1234::urn:schemas-tpv-org:service:PhotoFrame:1\r\n"))
        assertFalse(Dlna.isRendererMessage("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\nUSN: uuid:1::upnp:rootdevice\r\n"))
    }

    @Test fun lectureAllumee() {
        assertTrue(Dlna.isRendererMessage("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:1::urn:schemas-upnp-org:device:MediaRenderer:1\r\n"))
        assertTrue(Dlna.isRendererMessage("NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:service:AVTransport:1\r\nNTS: ssdp:alive\r\n"))
    }
}

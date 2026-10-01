package dev.thiagosindra.cloudlug.tools.driveauth

import dev.thiagosindra.cloudlug.provider.Pkce
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadRedirectTest {

    private val challenge = Pkce.newChallenge()
    private val state = Pkce.urlEncode(challenge.state)

    @Test
    fun `our state and a code is a code`() {
        assertEquals(Redirect.Code("4/abc"), readRedirect("state=$state&code=4%2Fabc&scope=x", challenge))
    }

    @Test
    fun `a code with someone else's state is refused before the code is read`() {
        val other = Pkce.urlEncode(Pkce.newChallenge().state)
        assertEquals(Redirect.StateMismatch, readRedirect("state=$other&code=4%2Fabc", challenge))
        assertEquals(Redirect.StateMismatch, readRedirect("code=4%2Fabc", challenge))
    }

    @Test
    fun `a declined consent is reported as what Google said`() {
        assertEquals(Redirect.Refused("access_denied"), readRedirect("state=$state&error=access_denied", challenge))
    }

    @Test
    fun `our state and nothing else is empty`() {
        assertEquals(Redirect.Empty, readRedirect("state=$state", challenge))
    }
}

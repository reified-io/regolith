package io.reified.regolith.egress

import io.reified.regolith.egress.support.FakeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DnsTest {
    @Test
    fun `a query reads back as its question`() {
        val question = checkNotNull(Dns.question(Dns.query(0x1234, "files.example.com")))

        assertEquals(0x1234, question.id)
        assertEquals("files.example.com", question.name)
    }

    @Test
    fun `a refusal keeps the id and the question and carries the code`() {
        val query = Dns.query(77, "blocked.example.org")
        val question = checkNotNull(Dns.question(query))
        val refusal = Dns.refusal(query, question, Dns.NXDOMAIN)

        assertEquals(77, Dns.u16(refusal, 0))
        assertEquals(Dns.NXDOMAIN, Dns.u16(refusal, 2) and 0xF)
        assertEquals(0x8000, Dns.u16(refusal, 2) and 0x8000)
        assertEquals(listOf(1, 0, 0, 0), listOf(4, 6, 8, 10).map { Dns.u16(refusal, it) })
        assertEquals("blocked.example.org", Dns.question(refusal.also { it[2] = 0 })?.name)
    }

    @Test
    fun `the addresses of an answer are read through compressed names`() {
        val answer = FakeResolver.answer(Dns.query(9, "www.example.com"), listOf("203.0.113.7", "203.0.113.8"))

        assertEquals(
            listOf("203.0.113.7", "203.0.113.8"),
            Dns.addresses(answer, answer.size, 9)?.map(Ipv4::text),
        )
    }

    @Test
    fun `an answer to another id or a failed one gives nothing`() {
        val answer = FakeResolver.answer(Dns.query(9, "www.example.com"), listOf("203.0.113.7"))

        assertNull(Dns.addresses(answer, answer.size, 10))
        assertNull(Dns.addresses(answer, 20, 9))
    }

    @Test
    fun `a response or a truncated packet is not a question`() {
        val query = Dns.query(5, "example.com")

        assertNull(Dns.question(query, 14))
        assertNull(Dns.question(FakeResolver.answer(query, emptyList())))
    }
}

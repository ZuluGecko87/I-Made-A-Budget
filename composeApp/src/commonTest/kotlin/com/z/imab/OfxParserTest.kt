package com.z.imab

import com.z.imab.importer.OfxParser
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfxParserTest {
    @Test
    fun parsesSgmlQbo() {
        val statements = OfxParser.parse(Samples.QBO_CHECKING)
        assertEquals(1, statements.size)
        val s = statements[0]
        assertEquals("bank-123456789-••1222", s.accountKey, "only the bank id and last 4 digits")
        assertEquals(false, s.isCreditCard)
        assertEquals(152034L, s.ledgerBalance)
        assertEquals(3, s.transactions.size)

        val first = s.transactions[0]
        assertEquals("20261001001", first.fitId)
        assertEquals(LocalDate(2026, 10, 1), first.date)
        assertEquals(-4512L, first.amount)
        assertEquals("KROGER #123", first.name)

        val paycheck = s.transactions[1]
        assertEquals(250000L, paycheck.amount)
        assertEquals("ACME PAYROLL & CO", paycheck.name)
    }

    @Test
    fun parsesXmlCreditCard() {
        val s = OfxParser.parse(Samples.OFX_XML_CREDIT_CARD).single()
        assertTrue(s.isCreditCard)
        assertEquals("card-••1111", s.accountKey)
        assertEquals(2, s.transactions.size)
        assertEquals(-1999L, s.transactions[0].amount)
        assertEquals("NETFLIX.COM", s.transactions[0].name)
        // Payee nested in <PAYEE><NAME> when there's no top-level NAME.
        assertEquals("CITY WATER DEPT", s.transactions[1].name)
    }

    @Test
    fun rejectsNonOfx() {
        assertFailsWith<IllegalArgumentException> { OfxParser.parse("Date,Amount\n2026-01-01,5") }
    }

    @Test
    fun parsesDates() {
        assertEquals(LocalDate(2026, 10, 15), OfxParser.parseDate("20261015120000.000[-5:EST]"))
        assertEquals(LocalDate(2026, 1, 2), OfxParser.parseDate("20260102"))
        assertEquals(null, OfxParser.parseDate("2026"))
    }
}

object Samples {
    val QBO_CHECKING = """
        OFXHEADER:100
        DATA:OFXSGML
        VERSION:102
        SECURITY:NONE
        ENCODING:USASCII
        CHARSET:1252
        COMPRESSION:NONE
        OLDFILEUID:NONE
        NEWFILEUID:NONE

        <OFX>
        <SIGNONMSGSRSV1><SONRS><STATUS><CODE>0<SEVERITY>INFO</STATUS><DTSERVER>20261015120000<LANGUAGE>ENG<INTU.BID>3000</SONRS></SIGNONMSGSRSV1>
        <BANKMSGSRSV1>
        <STMTTRNRS>
        <TRNUID>1
        <STATUS><CODE>0<SEVERITY>INFO</STATUS>
        <STMTRS>
        <CURDEF>USD
        <BANKACCTFROM>
        <BANKID>123456789
        <ACCTID>000111222
        <ACCTTYPE>CHECKING
        </BANKACCTFROM>
        <BANKTRANLIST>
        <DTSTART>20261001
        <DTEND>20261015
        <STMTTRN>
        <TRNTYPE>DEBIT
        <DTPOSTED>20261001120000.000[-5:EST]
        <TRNAMT>-45.12
        <FITID>20261001001
        <NAME>KROGER #123
        <MEMO>POS PURCHASE
        </STMTTRN>
        <STMTTRN>
        <TRNTYPE>CREDIT
        <DTPOSTED>20261003
        <TRNAMT>2500.00
        <FITID>20261003001
        <NAME>ACME PAYROLL &amp; CO
        </STMTTRN>
        <STMTTRN>
        <TRNTYPE>DEBIT
        <DTPOSTED>20261005
        <TRNAMT>-1200.00
        <FITID>20261005001
        <NAME>ONLINE TRANSFER TO SAVINGS
        </STMTTRN>
        </BANKTRANLIST>
        <LEDGERBAL><BALAMT>1520.34<DTASOF>20261015</LEDGERBAL>
        </STMTRS>
        </STMTTRNRS>
        </BANKMSGSRSV1>
        </OFX>
    """.trimIndent()

    val OFX_XML_CREDIT_CARD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <?OFX OFXHEADER="200" VERSION="220" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
        <OFX>
          <CREDITCARDMSGSRSV1>
            <CCSTMTTRNRS>
              <CCSTMTRS>
                <CURDEF>USD</CURDEF>
                <CCACCTFROM><ACCTID>4111XXXX1111</ACCTID></CCACCTFROM>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20261002</DTPOSTED>
                    <TRNAMT>-19.99</TRNAMT>
                    <FITID>CC-1</FITID>
                    <NAME>NETFLIX.COM</NAME>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20261004</DTPOSTED>
                    <TRNAMT>-62.10</TRNAMT>
                    <FITID>CC-2</FITID>
                    <PAYEE><NAME>CITY WATER DEPT</NAME><ADDR1>1 MAIN ST</ADDR1></PAYEE>
                  </STMTTRN>
                </BANKTRANLIST>
                <LEDGERBAL><BALAMT>-82.09</BALAMT><DTASOF>20261010</DTASOF></LEDGERBAL>
              </CCSTMTRS>
            </CCSTMTTRNRS>
          </CREDITCARDMSGSRSV1>
        </OFX>
    """.trimIndent()
}

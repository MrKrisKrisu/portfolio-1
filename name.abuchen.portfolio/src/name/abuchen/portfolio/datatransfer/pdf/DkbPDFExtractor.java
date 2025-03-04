package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Block;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.Transaction;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.util.TextUtil;

@SuppressWarnings("nls")
public class DkbPDFExtractor extends AbstractPDFExtractor
{
    private static final String IS_JOINT_ACCOUNT = "isJointAccount"; //$NON-NLS-1$
    private static final String EXCHANGE_RATE = "exchangeRate"; //$NON-NLS-1$
    private static final String FLAG_WITHHOLDING_TAX_FOUND  = "isHoldingTax"; //$NON-NLS-1$

    BiConsumer<Map<String, String>, String[]> isJointAccount = (context, lines) -> {
        Pattern pJointAccount = Pattern.compile("^Anteilige Berechnungsgrundlage für \\(50,00([\\s]+)?%\\).*$"); //$NON-NLS-1$
        Boolean bJointAccount = Boolean.FALSE;
        
        for (String line : lines)
        {
            Matcher m = pJointAccount.matcher(line);
            if (m.matches())
            {
                context.put(IS_JOINT_ACCOUNT, Boolean.TRUE.toString());
                bJointAccount = Boolean.TRUE;
                break;
            }
        }

        if (!bJointAccount)
            context.put(IS_JOINT_ACCOUNT, Boolean.FALSE.toString());

    };

    public DkbPDFExtractor(Client client)
    {
        super(client);

        addBankIdentifier("DKB"); //$NON-NLS-1$
        addBankIdentifier("Deutsche Kreditbank"); //$NON-NLS-1$
        addBankIdentifier("10919 Berlin"); //$NON-NLS-1$

        addBuySellTransaction();
        addDividendeTransaction();
        addTransferOutTransaction();
        addAdvanceTaxTransaction();
        addBuyTransactionFundsSavingsPlan();
        addAccountStatementTransaction();
        addCreditcardStatementTransaction();
    }

    @Override
    public String getLabel()
    {
        return "Deutsche Kreditbank AG"; //$NON-NLS-1$
    }

    private void addBuySellTransaction()
    {
        DocumentType type = new DocumentType("(Kauf|"
                        + "Kauf Direkthandel|"
                        + "Ausgabe|"
                        + "Verkauf|"
                        + "Verkauf Direkthandel|"
                        + "Verkauf aus Kapitalmaßnahme|"
                        + "Rücknahme Investmentfonds|"
                        + "Gesamtkündigung|"
                        + "Teilrückzahlung mit Nennwertänderung|"
                        + "Teilliquidation mit Nennwertreduzierung)", isJointAccount);
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });

        // Handshake for tax refund transaction
        Map<String, String> context = type.getCurrentContext();

        Block firstRelevantLine = new Block("^(Wertpapier Abrechnung "
                        + "(Kauf|"
                        + "Kauf Direkthandel|"
                        + "Ausgabe Investmentfonds|"
                        + "Verkauf|"
                        + "Verkauf Direkthandel|"
                        + "Verkauf aus Kapitalmaßnahme|"
                        + "R.cknahme Investmentfonds)|"
                        + "Gesamtk.ndigung|"
                        + "Teilr.ckzahlung mit Nennwert.nderung|"
                        + "Teilliquidation mit Nennwertreduzierung)$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Is type --> "Verkauf" change from BUY to SELL
                .section("type").optional()
                .match("^Wertpapier Abrechnung (?<type>(Kauf|"
                                + "Kauf Direkthandel|"
                                + "Ausgabe Investmentfonds|"
                                + "Verkauf|"
                                + "Verkauf Direkthandel|"
                                + "Verkauf aus Kapitalmaßnahme|"
                                + "R.cknahme Investmentfonds))$")
                .assign((t, v) -> {
                    if (v.get("type").equals("Verkauf") 
                                    || v.get("type").equals("Verkauf Direkthandel")
                                    || v.get("type").equals("Verkauf aus Kapitalmaßnahme")
                                    || v.get("type").equals("Rücknahme Investmentfonds"))
                    {
                        t.setType(PortfolioTransaction.Type.SELL);
                    }
                })

                // Is type --> "Gesamtkündigung" change from BUY to SELL
                .section("type").optional()
                .match("^(?<type>(Gesamtk.ndigung|Teilr.ckzahlung mit Nennwert.nderung|Teilliquidation mit Nennwertreduzierung))$")
                .assign((t, v) -> {
                    if (v.get("type").equals("Gesamtkündigung")
                                    || v.get("type").equals("Teilrückzahlung mit Nennwertänderung")
                                    || v.get("type").equals("Teilliquidation mit Nennwertreduzierung"))
                    {
                        t.setType(PortfolioTransaction.Type.SELL);
                    }
                })

                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // EUR 2.000,00 8,75 % METALCORP GROUP B.V. DE000A1HLTD2 (A1HLTD)
                // EO-ANLEIHE 2013(18)
                // Kurswert 1.950,00- EUR
                .section("name", "isin", "wkn", "nameContinued", "currency")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("(?<nameContinued>.*)")
                .match("^(Kurswert|R.ckzahlungsbetrag) [\\.,\\d]+([+|-])? (?<currency>[\\w]{3})(.*)?$")
                .assign((t, v) -> {
                    t.setSecurity(getOrCreateSecurity(v));

                    // Handshake, if there is a tax refund
                    context.put("name", v.get("name"));
                    context.put("nameContinued", v.get("nameContinued"));
                    context.put("isin", v.get("isin"));
                    context.put("wkn", v.get("wkn"));
                })

                // EUR 2.000,00 8,75 % METALCORP GROUP B.V. DE000A1HLTD2 (A1HLTD)
                // Stück 29,2893 COMSTAGE-MSCI WORLD TRN U.ETF LU0392494562 (ETF110)
                .section("notation", "shares")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(?<notation>St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) .*$")
                .assign((t, v) -> {
                    // Workaround for bonds
                    if (v.get("notation") != null && !v.get("notation").equalsIgnoreCase("Stück"))
                        t.setShares((asShares(v.get("shares")) / 100));
                    else
                        t.setShares(asShares(v.get("shares")));

                    // Handshake, if there is a tax refund
                    context.put("shares", v.get("shares"));
                })

                // Den Gegenwert buchen wir mit Valuta 09.07.2020 zu Gunsten des Kontos 1053412345
                // Den Betrag buchen wir mit Valuta 31.07.2014 zu Gunsten des Kontos 16765097 (IBAN DE30 1203 0000 0026 6741 97),
                .section("date").optional()
                .match("^Den (Gegenwert|Betrag) buchen wir mit Valuta (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> t.setDate(asDate(v.get("date"))))

                // Schlusstag/-Zeit 25.11.2015 11:02:54 Zinstermin Monat(e) 27. Juni
                .section("time").optional()
                .match("^Schlusstag(\\/-Zeit)? .* (?<time>[\\d]{2}:[\\d]{2}:[\\d]{2}) .*$")
                .assign((t, v) -> type.getCurrentContext().put("time", v.get("time")))

                // Schlusstag 06.03.2017 Auftraggeber Max Mustermann
                .section("date").optional()
                .match("^Schlusstag(\\/-Zeit)? (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> {
                    if (type.getCurrentContext().get("time") != null)
                        t.setDate(asDate(v.get("date"), type.getCurrentContext().get("time")));
                    else
                        t.setDate(asDate(v.get("date")));
                })

                // Ausmachender Betrag 4.937,19 EUR
                // Ausmachender Betrag 2.974,39+ EUR
                .section("amount", "currency").optional()
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)([+|-])? (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(v.get("currency"));
                })

                // Limit 1,75 EUR
                // Rückzahlungskurs 100 % Rückzahlungsdatum 31.07.2014
                .section("note").optional()
                .match("^(?<note>(Limit|R.ckzahlungskurs) [\\.,\\d]+ ([\\w]{3}|%))(.*)?$")
                .assign((t, v) -> t.setNote(v.get("note")))

                .wrap(BuySellEntryItem::new);

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);
        addTaxReturnBlock(context, type);
    }

    private void addDividendeTransaction()
    {
        DocumentType type = new DocumentType("(Dividendengutschrift|"
                        + "Zinsgutschrift|"
                        + "Gutschrift von Investmenterträgen|"
                        + "Ausschüttung aus Genussschein|"
                        + "Ausschüttung Investmentfonds|"
                        + "Ertragsgutschrift nach § 27 KStG|"
                        + "Gutschrift|"
                        + "Erträgnisgutschrift aus Wertpapieren)", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("^(Dividendengutschrift|"
                        + "Zinsgutschrift|"
                        + "Gutschrift von Investmentertr.gen|"
                        + "Aussch.ttung aus Genussschein|"
                        + "Aussch.ttung Investmentfonds|"
                        + "Ertragsgutschrift nach § 27 KStG|"
                        + "Gutschrift|"
                        + "Ertr.gnisgutschrift aus Wertpapieren)$");
        type.addBlock(block);
        Transaction<AccountTransaction> pdfTransaction = new Transaction<AccountTransaction>()
            .subject(() -> {
                AccountTransaction entry = new AccountTransaction();
                entry.setType(AccountTransaction.Type.DIVIDENDS);
                return entry;
            });

        pdfTransaction
                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // EUR 10.000,00 PCC SE DE000A1R1AN5 (A1R1AN)
                // INH.-TEILSCHULDV. V.13(13/17)
                // Zinsertrag 181,25+ EUR
                .section("name", "isin", "wkn", "nameContinued", "currency").optional()
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("(?<nameContinued>.*)")
                .match("^(Zinsertrag|Zahlbarkeitstag .*) [\\.,\\d]+([+])? (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // Stück 10,6841 SPDR S&P US DIVID.ARISTOCR.ETF
                // REGISTERED SHARES O.N.
                // IE00B6YX5D40 (A1JKS0)
                // Ertrag pro St. 0,230700000 USD
                .section("name", "isin", "wkn", "nameContinued", "currency").optional()
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) (?<name>.*)$")
                .match("(?<nameContinued>.*)")
                .match("^(?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("^Ertrag pro St. [\\.,\\d]+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // EUR 10.000,00 PCC SE DE000A1R1AN5 (A1R1AN)
                .section("notation", "shares")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(?<notation>St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) .*$")
                .assign((t, v) -> {
                    // Workaround for bonds
                    if (v.get("notation") != null && !v.get("notation").equalsIgnoreCase("Stück"))
                        t.setShares((asShares(v.get("shares")) / 100));
                    else
                        t.setShares(asShares(v.get("shares")));
                })

                // Den Betrag buchen wir mit Wertstellung 04.01.2016 zu Gunsten des Kontos 12345678 (IBAN DE30 1203 0000 0012 3456 
                .section("date")
                .match("^Den Betrag buchen wir mit Wertstellung (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                // Ausmachender Betrag 144,52+ EUR
                .section("amount", "currency")
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)[+] (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(v.get("currency"));
                })

                // Devisenkurs EUR / CHF 1,1959
                // Ausschüttung 51,00 CHF 42,65+ EUR
                .section("exchangeRate", "fxAmount", "fxCurrency", "amount", "currency").optional()
                .match("^Devisenkurs [\\w]{3} \\/ [\\w]{3} (?<exchangeRate>[\\.,\\d]+)(.*)?$")
                .match("^(Aussch.ttung|Dividendengutschrift|Kurswert) (?<fxAmount>[\\.,\\d]+) (?<fxCurrency>[\\w]{3}) (?<amount>[\\.,\\d]+)[+] (?<currency>[\\w]{3})")
                .assign((t, v) -> {
                    BigDecimal exchangeRate = asExchangeRate(v.get("exchangeRate"));
                    if (t.getCurrencyCode().contentEquals(asCurrencyCode(v.get("fxCurrency"))))
                    {
                        exchangeRate = BigDecimal.ONE.divide(exchangeRate, 10, RoundingMode.HALF_DOWN);
                    }
                    type.getCurrentContext().put("exchangeRate", exchangeRate.toPlainString());

                    if (!t.getCurrencyCode().equals(t.getSecurity().getCurrencyCode()))
                    {
                        BigDecimal inverseRate = BigDecimal.ONE.divide(exchangeRate, 10,
                                        RoundingMode.HALF_DOWN);

                        // check, if forex currency is transaction
                        // currency or not and swap amount, if necessary
                        Unit grossValue;
                        if (!asCurrencyCode(v.get("fxCurrency")).equals(t.getCurrencyCode()))
                        {
                            Money fxAmount = Money.of(asCurrencyCode(v.get("fxCurrency")),
                                            asAmount(v.get("fxAmount")));
                            Money amount = Money.of(asCurrencyCode(v.get("currency")),
                                            asAmount(v.get("amount")));
                            grossValue = new Unit(Unit.Type.GROSS_VALUE, amount, fxAmount, inverseRate);
                        }
                        else
                        {
                            Money amount = Money.of(asCurrencyCode(v.get("fxCurrency")),
                                            asAmount(v.get("fxAmount")));
                            Money fxAmount = Money.of(asCurrencyCode(v.get("currency")),
                                            asAmount(v.get("amount")));
                            grossValue = new Unit(Unit.Type.GROSS_VALUE, amount, fxAmount, inverseRate);
                        }
                        t.addUnit(grossValue);
                    }
                })

                // Ex-Tag 09.02.2017 Art der Dividende Quartalsdividende
                .section("note").optional()
                .match("^.* Art der Dividende (?<note>.*)$")
                .assign((t, v) -> t.setNote(v.get("note")))

                // Kapitalrückzahlung
                .section("note").optional()
                .match("^(?<note>Kapitalr.ckzahlung)$")
                .assign((t, v) -> t.setNote(v.get("note")))

                .wrap(TransactionItem::new);

        addTaxesSectionsTransaction(pdfTransaction, type);
        addFeesSectionsTransaction(pdfTransaction, type);

        block.set(pdfTransaction);
    }

    private void addTransferOutTransaction()
    {
        DocumentType type = new DocumentType("Depotbuchung - Belastung", isJointAccount);
        this.addDocumentTyp(type);

        Block block = new Block("^Depotbuchung - Belastung$");
        type.addBlock(block);
        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.TRANSFER_OUT);
            return entry;
        });

        pdfTransaction
                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // EUR 25.000,00 24,75 % UBS AG (LONDON BRANCH) DE000US9RGR9 (US9RGR)
                // EO-ANL. 14(16) RWE
                .section("currency", "shares", "name", "isin", "wkn", "nameContinued")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(?<currency>[\\w]{3}) (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("(?<nameContinued>.*)")
                .assign((t, v) -> {
                    // Workaround for bonds
                    t.setShares((asShares(v.get("shares")) / 100));
                    t.setSecurity(getOrCreateSecurity(v));

                    // Workaround for bonds
                    t.setAmount(0L);
                    t.setCurrencyCode(asCurrencyCode(t.getPortfolioTransaction().getSecurity().getCurrencyCode()));
                })

                // Valuta 30.11.2015 externe Referenz-Nr. KP40030120300340
                .section("date")
                .match("^Valuta (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> t.setDate(asDate(v.get("date"))))

                // Depotkonto-Nr.
                .section("note").optional()
                .match("^(?<note>Depotkonto-Nr. .*)$")
                .assign((t, v) -> t.setNote(v.get("note")))

                .wrap(BuySellEntryItem::new);

        block.set(pdfTransaction);
    }

    private void addAdvanceTaxTransaction()
    {
        DocumentType type = new DocumentType("Vorabpauschale Investmentfonds");
        this.addDocumentTyp(type);

        Transaction<AccountTransaction> pdfTransaction = new Transaction<>();
        pdfTransaction.subject(() -> {
            AccountTransaction entry = new AccountTransaction();
            entry.setType(AccountTransaction.Type.TAXES);
            return entry;
        });

        Block firstRelevantLine = new Block("^Vorabpauschale Investmentfonds$");
        type.addBlock(firstRelevantLine);
        firstRelevantLine.set(pdfTransaction);

        pdfTransaction
                // Nominale Wertpapierbezeichnung ISIN (WKN)
                // Stück 49,1102 VANGUARD FTSE ALL-WORLD U.ETF IE00BK5BQT80 (A2PKXG)
                // REG. SHS USD ACC. ON
                // Zahlbarkeitstag 04.01.2021 Vorabpauschale pro St. 0,037971560 EUR
                .section("name", "isin", "wkn", "nameContinued", "currency")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) (?<name>.*) (?<isin>[\\w]{12}) \\((?<wkn>.*)\\)$")
                .match("(?<nameContinued>.*)")
                .match("^.* Vorabpauschale pro St. [\\.,\\d]+ (?<currency>[\\w]{3})$")
                .assign((t, v) -> t.setSecurity(getOrCreateSecurity(v)))

                // EUR 2.000,00 8,75 % METALCORP GROUP B.V. DE000A1HLTD2 (A1HLTD)
                // Stück 29,2893 COMSTAGE-MSCI WORLD TRN U.ETF LU0392494562 (ETF110)
                .section("notation", "shares")
                .find("Nominale Wertpapierbezeichnung ISIN \\(WKN\\)")
                .match("^(?<notation>St.ck|[\\w]{3}) (?<shares>[\\.,\\d]+) .*$")
                .assign((t, v) -> {
                    // Workaround for bonds
                    if (v.get("notation") != null && !v.get("notation").equalsIgnoreCase("Stück"))
                        t.setShares((asShares(v.get("shares")) / 100));
                    else
                        t.setShares(asShares(v.get("shares")));
                })

                // Den Betrag buchen wir mit Wertstellung 06.01.2021 zu Lasten des Kontos 1234567890 (IBAN DE99 9999 9999 9999 9999
                .section("date")
                .match("^Den Betrag buchen wir mit Wertstellung (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> t.setDateTime(asDate(v.get("date"))))

                // Ausmachender Betrag 0,08- EUR
                .section("amount", "currency").optional()
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(v.get("currency"));
                })

                .wrap(t -> new TransactionItem(t));
    }

    private void addBuyTransactionFundsSavingsPlan()
    {
        final DocumentType type = new DocumentType("Halbjahresabrechnung Sparplan", (context, lines) -> {
            Pattern pSecurity = Pattern.compile("(?<name>.*) (?<isin>[\\w]{12}) (\\((?<wkn>.*)\\).*)$");
            Pattern pCurrency = Pattern.compile("^(?<currency>[\\w]{3}) in .*$");
            // read the current context here
            for (String line : lines)
            {
                Matcher m = pSecurity.matcher(line);
                if (m.matches())
                {
                    context.put("isin", m.group("isin"));
                    context.put("wkn", m.group("wkn"));
                    context.put("name", m.group("name"));
                }

                m = pCurrency.matcher(line);
                if (m.matches())
                {
                    context.put("currency", m.group("currency"));
                }
            }
        });
        this.addDocumentTyp(type);

        Transaction<BuySellEntry> pdfTransaction = new Transaction<>();

        Block blockTransaction = new Block("Kauf [\\.,\\d]+ .*");
        type.addBlock(blockTransaction);
        pdfTransaction.subject(() -> {
            BuySellEntry entry = new BuySellEntry();
            entry.setType(PortfolioTransaction.Type.BUY);
            return entry;
        });
        blockTransaction.set(pdfTransaction);

        pdfTransaction
                // Kauf 90,00 531781/77.00 40,1900 1,0000 2,2394 05.07.2018 09.07.2018 0,00 0,00
                .section("amount", "shares", "date", "fee").optional()
                .match("^Kauf (?<amount>[\\.,\\d]+) [\\d]{2,10}\\/.* (?<shares>[\\.,\\d]+) (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .*$")
                .match("^.* Provision (?<fee>[\\.,\\d]+) .*$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();

                    t.setSecurity(getOrCreateSecurity(context));
                    t.setDate(asDate(v.get("date")));
                    t.setShares(asShares(v.get("shares")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(asCurrencyCode(context.get("currency")));

                    Money feeAmount = Money.of(asCurrencyCode(v.get("currencyFee")), asAmount(v.get("fee")));
                    t.getPortfolioTransaction().addUnit(new Unit(Unit.Type.FEE, feeAmount));
                })

                // Kauf 200,00 256485/46.00 51,1040 1,0000 3,9136 20.02.2020 24.02.2020 0,00 0,00
                // + Provision 0,49 Summe 200,49
                .section("amount", "shares", "date").optional()
                .match("^Kauf (?<amount>[\\.,\\d]+) [\\d]{2,10}\\/.* (?<shares>[\\.,\\d]+) (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} .*$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();

                    t.setSecurity(getOrCreateSecurity(context));
                    t.setDate(asDate(v.get("date")));
                    t.setShares(asShares(v.get("shares")));

                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(asCurrencyCode(context.get("currency")));
                })

                .wrap(BuySellEntryItem::new);
    }

    private void addAccountStatementTransaction()
    {
        final DocumentType type = new DocumentType("Kontoauszug Nummer", (context, lines) -> {
            Pattern pCurrency = Pattern.compile("^Bu.Tag Wert Wir haben f.r Sie gebucht Belastung in (?<currency>[\\w]{3}).*$");
            Pattern pYear = Pattern.compile("^Kontoauszug Nummer (?<nr>[\\d]{3}) / (?<year>[\\d]{4}) vom [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} bis [\\d]{2}\\.[\\d]{2}\\.[\\d]{4}$");
            Pattern pAccountingBillDate = Pattern.compile("^Abrechnung (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4})$");
            // read the current context here
            for (String line : lines)
            {
                Matcher m = pCurrency.matcher(line);
                if (m.matches())
                {
                    context.put("currency", m.group("currency"));
                }

                m = pYear.matcher(line);
                if (m.matches())
                {
                    context.put("nr", m.group("nr"));
                    // Read year
                    context.put("year", m.group("year"));
                }

                m = pAccountingBillDate.matcher(line);
                if (m.matches())
                {
                    context.put("accountingBillDate", m.group("date"));
                }
            }
        });
        this.addDocumentTyp(type);

        Block interestChargeBlock = new Block("^Abrechnungszeitraum vom [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} bis [\\d]{2}\\.[\\d]{2}\\.[\\d]{4}$");
        type.addBlock(interestChargeBlock);
        interestChargeBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.INTEREST);
                    return entry;
                })

                .section("note", "amount", "type").optional()
                .match("^(?<note>Abrechnungszeitraum vom [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} bis [\\d]{2}\\.[\\d]{2}\\.[\\d]{4})$")
                .match("^(Zinsen für Guthaben|Zinsen f.r einger.umte Konto.berziehung) ([\\s]+)?(?<amount>[\\.,\\d]+)(?<type>(\\+|\\-))$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    if (v.get("type").equals("-"))
                    {
                        t.setType(AccountTransaction.Type.INTEREST_CHARGE);
                    }

                    t.setDateTime(asDate(context.get("accountingBillDate")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));

        Block interestChargeCreditBlock = new Block("^Zinsen für Dispositionskredit ([\\s]+)?[\\.,\\d]+[\\-|\\-]$");
        type.addBlock(interestChargeCreditBlock);
        interestChargeCreditBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.INTEREST);
                    return entry;
                })

                .section("note", "amount", "type")
                .match("^(?<note>Zinsen für Dispositionskredit) ([\\s]+)?(?<amount>[\\.,\\d]+)(?<type>(\\+|\\-))$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    if (v.get("type").equals("-"))
                    {
                        t.setType(AccountTransaction.Type.INTEREST_CHARGE);
                    }

                    t.setDateTime(asDate(context.get("accountingBillDate")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));

        Block taxesBlock = new Block("^Kapitalertragsteuer ([\\s]+)?[\\.,\\d]+[\\-|\\-]$");
        type.addBlock(taxesBlock);
        taxesBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.TAXES);
                    return entry;
                })

                .section("note", "amount", "type")
                .match("^(?<note>Kapitalertragsteuer) ([\\s]+)?(?<amount>[\\.,\\d]+)(?<type>(\\+|\\-))$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    if (v.get("type").equals("+"))
                    {
                        t.setType(AccountTransaction.Type.TAX_REFUND);
                    }

                    t.setDateTime(asDate(context.get("accountingBillDate")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));

        Block removalBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\. [\\d]{2}\\.[\\d]{2}\\. (.berweisung|Dauerauftrag|Basislastschrift|Kartenzahlung|Kreditkartenabr.) [\\.,\\d]+$");
        type.addBlock(removalBlock);
        removalBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.REMOVAL);
                    return entry;
                })

                .section("month1", "day", "month2", "note", "amount")
                .match("^[\\d]{2}\\.(?<month1>[\\d]{2})\\. (?<day>[\\d]{2})\\.(?<month2>[\\d]{2})\\. (?<note>.berweisung|Dauerauftrag|Basislastschrift|Kartenzahlung|Kreditkartenabr.) (?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    // since year is not within the date correction
                    // necessary in first receipt of year
                    if (context.get("nr").compareTo("001") == 0 && Integer.parseInt(v.get("month1")) != Integer.parseInt(v.get("month2")))
                    {
                        Integer year = Integer.parseInt(context.get("year")) - 1;
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + year.toString()));
                    }
                    else
                    {
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + context.get("year")));
                    }
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(TransactionItem::new));

        Block depositBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\. [\\d]{2}\\.[\\d]{2}\\. (Lohn, Gehalt, Rente|Zahlungseingang|Bareinzahlung am GA|sonstige Buchung|Eingang Echtzeit.berw) [\\.,\\d]+$");
        type.addBlock(depositBlock);
        depositBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.DEPOSIT);
                    return entry;
                })

                .section("month1", "day", "month2", "note", "amount")
                .match("^[\\d]{2}\\.(?<month1>[\\d]{2})\\. (?<day>[\\d]{2})\\.(?<month2>[\\d]{2})\\. (?<note>Lohn, Gehalt, Rente|Zahlungseingang|Bareinzahlung am GA|sonstige Buchung|Eingang Echtzeit.berw) (?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    // since year is not within the date correction
                    // necessary in first receipt of year
                    if (context.get("nr").compareTo("001") == 0 && Integer.parseInt(v.get("month1")) != Integer.parseInt(v.get("month2")))
                    {
                        Integer year = Integer.parseInt(context.get("year")) - 1;
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + year.toString()));
                    }
                    else
                    {
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + context.get("year")));
                    }
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(TransactionItem::new));

        Block taxreturnBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\. [\\d]{2}\\.[\\d]{2}\\. [\\d]+ Steuerausgleich [\\.,\\d]+$");
        type.addBlock(taxreturnBlock);
        taxreturnBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.TAX_REFUND);
                    return entry;
                })

                .section("month1", "day", "month2", "note", "amount")
                .match("^[\\d]{2}\\.(?<month1>[\\d]{2})\\. (?<day>[\\d]{2})\\.(?<month2>[\\d]{2})\\. [\\d]+ (?<note>Steuerausgleich) (?<amount>[\\.,\\d]+)$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    // since year is not within the date correction
                    // necessary in first receipt of year
                    if (context.get("nr").compareTo("001") == 0 && Integer.parseInt(v.get("month1")) != Integer.parseInt(v.get("month2")))
                    {
                        Integer year = Integer.parseInt(context.get("year")) - 1;
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + year.toString()));
                    }
                    else
                    {
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + context.get("year")));
                    }
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(TransactionItem::new));

        Block feesBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\. [\\d]{2}\\.[\\d]{2}\\. Rechnung [\\.,\\d]+$");
        type.addBlock(feesBlock);
        feesBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.FEES);
                    return entry;
                })

                .section("month1", "day", "month2", "note", "amount")
                .match("^[\\d]{2}\\.(?<month1>[\\d]{2})\\. (?<day>[\\d]{2})\\.(?<month2>[\\d]{2})\\. (?<note>Rechnung) (?<amount>[\\.,\\d]+)$")
                .match("^.* Bargeldeinzahlung .*$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    // since year is not within the date correction
                    // necessary in first receipt of year
                    if (context.get("nr").compareTo("001") == 0 && Integer.parseInt(v.get("month1")) != Integer.parseInt(v.get("month2")))
                    {
                        Integer year = Integer.parseInt(context.get("year")) - 1;
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + year.toString()));
                    }
                    else
                    {
                        t.setDateTime(asDate(v.get("day") + "." + v.get("month2") + "." + context.get("year")));
                    }
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(v.get("note"));
                })

                .wrap(TransactionItem::new));
    }

    private void addCreditcardStatementTransaction()
    {
        DocumentType type = new DocumentType("Ihre Abrechnung vom ", (context, lines) -> {
            Pattern pCurrency = Pattern.compile("^Beleg BuchungVerwendungszweck (?<currency>[\\w]{3})$");
            Pattern pcentury = Pattern.compile("^Ihre Abrechnung vom [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} bis [\\d]{2}\\.[\\d]{2}\\.[\\d]{4} Abrechnungsdatum: [\\d]{2}\\. .*(?<century>[\\d]{2})[\\d]{2}$");
            // read the current context here
            for (String line : lines)
            {
                Matcher m = pCurrency.matcher(line);
                if (m.matches())
                {
                    context.put("currency", m.group("currency"));
                }

                m = pcentury.matcher(line);
                if (m.matches())
                {
                    // Read century
                    context.put("century", m.group("century"));
                }
            }
        });
        this.addDocumentTyp(type);

        Block depositBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} [\\d]{2}\\.[\\d]{2}\\.[\\d]{2}(?! Habenzins).* [\\.,\\d]+\\+$");
        type.addBlock(depositBlock);
        depositBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.DEPOSIT);
                    return entry;
                })

                .oneOf(
                                section -> section
                                        .attributes("date", "note", "amount")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2}) (?<note>Ausgleich Kreditkarte gem\\. Abrechnung) v\\. (?<amount>[\\.,\\d]+)\\+$")
                                        .assign((t, v) -> {
                                            Map<String, String> context = type.getCurrentContext();
                                            v.put("note", v.get("note"));
        
                                            t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                                            + v.get("date").substring(6, 8)));
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(context.get("currency"));
                                            t.setNote(TextUtil.strip(v.get("note")));
                                        })
                                ,
                                section -> section
                                        .attributes("date", "note", "amount")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2})(?<note>(?! Habenzins).*) [\\w]{3} [\\.,\\d]+ [\\.,\\d]+ (?<amount>[\\.,\\d]+)\\+$")
                                        .assign((t, v) -> {
                                            Map<String, String> context = type.getCurrentContext();
                                            v.put("note", TextUtil.strip(v.get("note")));

                                            t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                                            + v.get("date").substring(6, 8)));
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(context.get("currency"));

                                            /***
                                             * Deletes characters that occur during 
                                             * withdrawals from foreign banks
                                             */
                                            if ("*".equals(v.get("note").substring(0, 1)))
                                                v.put("note", v.get("note").substring(1));
                                            
                                            if (">".equals(v.get("note").substring(v.get("note").length() - 1)))
                                                v.put("note", v.get("note").substring(0, v.get("note").length() - 1));

                                            t.setNote(TextUtil.strip(v.get("note")));
                                        })
                                ,
                                section -> section
                                        .attributes("date", "note", "amount")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2})(?<note>(?! Habenzins).*) (?<amount>[\\.,\\d]+)\\+$")
                                        .assign((t, v) -> {
                                            Map<String, String> context = type.getCurrentContext();
                                            v.put("note", TextUtil.strip(v.get("note")));

                                            t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                                            + v.get("date").substring(6, 8)));
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(context.get("currency"));

                                            /***
                                             * Deletes characters that occur during 
                                             * withdrawals from foreign banks
                                             */
                                            if ("*".equals(v.get("note").substring(0, 1)))
                                                v.put("note", v.get("note").substring(1));
                                            
                                            if (">".equals(v.get("note").substring(v.get("note").length() - 1)))
                                                v.put("note", v.get("note").substring(0, v.get("note").length() - 1));

                                            t.setNote(TextUtil.strip(v.get("note")));
                                        })
                            )

                .wrap(TransactionItem::new));

        Block interestBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} [\\d]{2}\\.[\\d]{2}\\.[\\d]{2} Habenzins auf [\\d]+ Tage [\\.,\\d]+\\+$");
        type.addBlock(interestBlock);
        interestBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.INTEREST);
                    return entry;
                })

                .section("date", "note", "amount")
                .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2}) (?<note>Habenzins auf [\\d]+ Tage) (?<amount>[\\.,\\d]+)\\+$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                    + v.get("date").substring(6, 8)));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(TextUtil.strip(v.get("note")));
                })

                .wrap(TransactionItem::new));

        Block taxesBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2}) Abgeltungsteuer [\\.,\\d]+ \\-$");
        type.addBlock(taxesBlock);
        taxesBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.TAXES);
                    return entry;
                })

                .section("date", "note", "amount")
                .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2}) (?<note>Abgeltungsteuer) (?<amount>[\\.,\\d]+) \\-$")
                .assign((t, v) -> {
                    Map<String, String> context = type.getCurrentContext();
                    t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                    + v.get("date").substring(6, 8)));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setCurrencyCode(context.get("currency"));
                    t.setNote(TextUtil.strip(v.get("note")));
                })

                .wrap(TransactionItem::new));

        Block removalBlock = new Block("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} [\\d]{2}\\.[\\d]{2}\\.[\\d]{2}(?! Abgeltungsteuer).* [\\.,\\d]+ \\-$");
        type.addBlock(removalBlock);
        removalBlock.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction entry = new AccountTransaction();
                    entry.setType(AccountTransaction.Type.REMOVAL);
                    return entry;
                })

                .oneOf(

                                section -> section
                                        .attributes("date", "note", "amount")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2})(?<note>(?! Abgeltungsteuer).*) [\\w]{3} [\\.,\\d]+ [\\.,\\d]+ (?<amount>[\\.,\\d]+) \\-$")
                                        .assign((t, v) -> {
                                            Map<String, String> context = type.getCurrentContext();
                                            v.put("note", TextUtil.strip(v.get("note")));

                                            t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                                            + v.get("date").substring(6, 8)));
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(context.get("currency"));

                                            /***
                                             * Deletes characters that occur during 
                                             * withdrawals from foreign banks
                                             */
                                            if ("*".equals(v.get("note").substring(0, 1)))
                                                v.put("note", v.get("note").substring(1));
                                            
                                            if (">".equals(v.get("note").substring(v.get("note").length() - 1)))
                                                v.put("note", v.get("note").substring(0, v.get("note").length() - 1));

                                            t.setNote(TextUtil.strip(v.get("note")));
                                        })
                                ,
                                section -> section
                                        .attributes("date", "note", "amount")
                                        .match("^[\\d]{2}\\.[\\d]{2}\\.[\\d]{2} (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{2})(?<note>(?! Abgeltungsteuer).*) (?<amount>[\\.,\\d]+) \\-$")
                                        .assign((t, v) -> {
                                            Map<String, String> context = type.getCurrentContext();
                                            v.put("note", TextUtil.strip(v.get("note")));

                                            t.setDateTime(asDate(v.get("date").substring(0, 6) + context.get("century")
                                                            + v.get("date").substring(6, 8)));
                                            t.setAmount(asAmount(v.get("amount")));
                                            t.setCurrencyCode(context.get("currency"));

                                            /***
                                             * Deletes characters that occur during 
                                             * withdrawals from foreign banks
                                             */
                                            if ("*".equals(v.get("note").substring(0, 1)))
                                                v.put("note", v.get("note").substring(1));
                                            
                                            if (">".equals(v.get("note").substring(v.get("note").length() - 1)))
                                                v.put("note", v.get("note").substring(0, v.get("note").length() - 1));

                                            t.setNote(TextUtil.strip(v.get("note")));
                                        })
                            )

                .wrap(TransactionItem::new));
    }

    private void addTaxReturnBlock(Map<String, String> context, DocumentType type)
    {
        Block block = new Block("^Steuerliche Ausgleichrechnung$");
        type.addBlock(block);
        block.set(new Transaction<AccountTransaction>()

                .subject(() -> {
                    AccountTransaction t = new AccountTransaction();
                    t.setType(AccountTransaction.Type.TAX_REFUND);
                    return t;
                })

                // Ausmachender Betrag 56,57 EUR
                // Den Gegenwert buchen wir mit Valuta 27.10.2015 zu Gunsten des Kontos 12345678
                .section("amount", "currency", "date").optional()
                .match("^Ausmachender Betrag (?<amount>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .match("^Den Gegenwert buchen wir mit Valuta (?<date>[\\d]{2}\\.[\\d]{2}\\.[\\d]{4}) .*$")
                .assign((t, v) -> {
                    t.setDateTime(asDate(v.get("date")));
                    t.setCurrencyCode(asCurrencyCode(v.get("currency")));
                    t.setAmount(asAmount(v.get("amount")));
                    t.setShares(asShares(context.get("shares")));

                    t.setSecurity(getOrCreateSecurity(context));
                })

                .wrap(t -> {
                    if (t.getCurrencyCode() != null && t.getAmount() != 0)
                        return new TransactionItem(t);
                    return null;
                }));
    }

    private <T extends Transaction<?>> void addTaxesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Kapitalertragsteuer (Account)
                // Kapitalertragsteuer 24,45% auf 1.718,79 EUR 420,24- EUR
                // Kapitalertragsteuer 24,45 % auf 131,25 EUR 32,09- EUR
                .section("tax", "currency").optional()
                .match("^Kapitalertragsteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kapitalerstragsteuer (Joint Account)
                // Kapitalertragsteuer 24,45% auf 1.718,79 EUR 420,24- EUR
                // Kapitalertragsteuer 24,45 % auf 131,25 EUR 32,09- EUR
                .section("tax1", "currency1", "tax2", "currency2").optional()
                .match("^Kapitalertragsteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax1>[\\.,\\d]+)- (?<currency1>[\\w]{3})$")
                .match("^Kapitalertragsteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax2>[\\.,\\d]+)- (?<currency2>[\\w]{3})$")
                .assign((t, v) -> {
                    if (Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        // Account 1
                        v.put("currency", v.get("currency1"));
                        v.put("tax", v.get("tax1"));
                        processTaxEntries(t, v, type);

                        // Account 2
                        v.put("currency", v.get("currency2"));
                        v.put("tax", v.get("tax2"));
                        processTaxEntries(t, v, type);
                    }
                })

                // Solidaritätszuschlag (Account)
                // Solidaritätszuschlag 5,50% auf 420,24 EUR 23,11- EUR
                // Solidaritätszuschlag 5,5 % auf 32,09 EUR 1,76- EUR
                .section("tax", "currency").optional()
                .match("^Solidarit.tszuschlag [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Solidaritätszuschlag (Joint Account)
                // Solidaritätszuschlag 5,50% auf 420,24 EUR 23,11- EUR
                // Solidaritätszuschlag 5,5 % auf 32,09 EUR 1,76- EUR
                .section("tax1", "currency1", "tax2", "currency2").optional()
                .match("^Solidarit.tszuschlag [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax1>[\\.,\\d]+)- (?<currency1>[\\w]{3})$")
                .match("^Solidarit.tszuschlag [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax2>[\\.,\\d]+)- (?<currency2>[\\w]{3})$")
                .assign((t, v) -> {
                    if (Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        // Account 1
                        v.put("currency", v.get("currency1"));
                        v.put("tax", v.get("tax1"));
                        processTaxEntries(t, v, type);

                        // Account 2
                        v.put("currency", v.get("currency2"));
                        v.put("tax", v.get("tax2"));
                        processTaxEntries(t, v, type);
                    }
                })

                // Kirchensteuer (Account)
                // Kirchensteuer 9,00% auf 420,24 EUR 37,82- EUR
                // Kirchensteuer 9 % auf 32,09 EUR 2,88- EUR
                .section("tax", "currency").optional()
                .match("^Kirchensteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> {
                    if (!Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        processTaxEntries(t, v, type);
                    }
                })

                // Kirchensteuer (Joint Account)
                // Kirchensteuer 9,00% auf 420,24 EUR 37,82- EUR
                // Kirchensteuer 9 % auf 32,09 EUR 2,88- EUR
                .section("tax1", "currency1", "tax2", "currency2").optional()
                .match("^Kirchensteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax1>[\\.,\\d]+)- (?<currency1>[\\w]{3})$")
                .match("^Kirchensteuer [\\.,\\d]+([\\s]+)?% .* [\\.,\\d]+ [\\w]{3} (?<tax2>[\\.,\\d]+)- (?<currency2>[\\w]{3})$")
                .assign((t, v) -> {
                    if (Boolean.parseBoolean(type.getCurrentContext().get(IS_JOINT_ACCOUNT)))
                    {
                        // Account 1
                        v.put("currency", v.get("currency1"));
                        v.put("tax", v.get("tax1"));
                        processTaxEntries(t, v, type);

                        // Account 2
                        v.put("currency", v.get("currency2"));
                        v.put("tax", v.get("tax2"));
                        processTaxEntries(t, v, type);
                    }
                })

                // Einbehaltene Quellensteuer 35 % auf 51,00 CHF 14,93- EUR
                .section("quellensteinbeh", "currency").optional()
                .match("^Einbehaltene Quellensteuer .* (?<quellensteinbeh>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) ->  {
                    type.getCurrentContext().put(FLAG_WITHHOLDING_TAX_FOUND, Boolean.TRUE.toString());
                    addTax(t, v, type, "quellensteinbeh");
                })

                // Anrechenbare Quellensteuer 15 % auf 42,65 EUR 6,40 EUR
                .section("quellenstanr", "currency").optional()
                .match("^Anrechenbare Quellensteuer .* (?<quellenstanr>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> addTax(t, v, type, "quellenstanr"))

                // 20 % rückforderbare Quellensteuer 10,20 CHF
                .section("quellenstrueck", "currency").optional()
                .match("^.* r.ckforderbare Quellensteuer (?<quellenstrueck>[\\.,\\d]+) (?<currency>[\\w]{3})$")
                .assign((t, v) -> addTax(t, v, type, "quellenstrueck"))

                // Finanztransaktionssteuer 5,71- EUR
                .section("quellenstrueck", "currency").optional()
                .match("^Finanztransaktionssteuer (?<quellenstrueck>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> addTax(t, v, type, "quellenstrueck"));
    }

    private <T extends Transaction<?>> void addFeesSectionsTransaction(T transaction, DocumentType type)
    {
        transaction
                // Provision 7,50- EUR
                .section("fee", "currency").optional()
                .match("^Provision (?<fee>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Transaktionsentgelt Börse 0,71- EUR
                .section("fee", "currency").optional()
                .match("^Transaktionsentgelt B.rse (?<fee>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Übertragungs-/Liefergebühr 0,20- EUR
                .section("fee", "currency").optional()
                .match("^Übertragungs-\\/Liefergeb.hr (?<fee>[-.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Fremde Abwicklungsgebühr für die Umschreibung von Namensaktien 0,60- EUR
                .section("fee", "currency").optional()
                .match("^Fremde Abwicklungsgeb.hr .* (?<fee>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Abwicklungskosten Börse 0,06- EUR
                .section("fee", "currency").optional()
                .match("^Abwicklungskosten B.rse (?<fee>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type))

                // Maklercourtage 0,0800 % vom Kurswert 1,67- EUR
                .section("fee", "currency").optional()
                .match("^Maklercourtage .* (?<fee>[\\.,\\d]+)- (?<currency>[\\w]{3})$")
                .assign((t, v) -> processFeeEntries(t, v, type));
    }

    private void addTax(Object t, Map<String, String> v, DocumentType type, String taxtype)
    {
        // Wenn es 'Einbehaltene Quellensteuer' gibt, dann die weiteren
        // Quellensteuer-Arten nicht berücksichtigen.
        // Die Berechnung der Gesamt-Quellensteuer anhand der anrechenbaren- und
        // der rückforderbaren Steuer kann ansonsten zu Rundungsfehlern führen.
        if (checkWithholdingTax(type, taxtype))
        {
            name.abuchen.portfolio.model.Transaction tx = getTransaction(t);

            String currency = asCurrencyCode(v.get("currency"));
            long amount = asAmount(v.get(taxtype));

            if (!currency.equals(tx.getCurrencyCode()) && type.getCurrentContext().containsKey(EXCHANGE_RATE))
            {
                BigDecimal rate = BigDecimal.ONE.divide(
                                asExchangeRate(type.getCurrentContext().get(EXCHANGE_RATE)), 10,
                                RoundingMode.HALF_DOWN);

                currency = tx.getCurrencyCode();
                amount = rate.multiply(BigDecimal.valueOf(amount)).setScale(0, RoundingMode.HALF_DOWN).longValue();
            }

            tx.addUnit(new Unit(Unit.Type.TAX, Money.of(currency, amount)));
        }
    }

    private boolean checkWithholdingTax(DocumentType type, String taxtype)
    {
        if (Boolean.valueOf(type.getCurrentContext().get(FLAG_WITHHOLDING_TAX_FOUND)))
        {
            if ("quellenstanr".equalsIgnoreCase(taxtype) || ("quellenstrueck".equalsIgnoreCase(taxtype)))
            {
                return false;
            }
        }
        return true;
    }

    private name.abuchen.portfolio.model.Transaction getTransaction(Object t)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            return ((name.abuchen.portfolio.model.Transaction) t);
        }
        else
        {
            return ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction();
        }
    }

    private void processTaxEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));
            PDFExtractorUtils.checkAndSetTax(tax,
                            (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money tax = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("tax")));
            PDFExtractorUtils.checkAndSetTax(tax,
                            ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }

    private void processFeeEntries(Object t, Map<String, String> v, DocumentType type)
    {
        if (t instanceof name.abuchen.portfolio.model.Transaction)
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee")));
            PDFExtractorUtils.checkAndSetFee(fee, 
                            (name.abuchen.portfolio.model.Transaction) t, type);
        }
        else
        {
            Money fee = Money.of(asCurrencyCode(v.get("currency")), asAmount(v.get("fee")));
            PDFExtractorUtils.checkAndSetFee(fee,
                            ((name.abuchen.portfolio.model.BuySellEntry) t).getPortfolioTransaction(), type);
        }
    }
}

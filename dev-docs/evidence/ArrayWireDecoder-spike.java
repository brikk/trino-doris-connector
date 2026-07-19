import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * ==========================================================================
 *  NON-PRODUCTION SPIKE. THROWAWAY EVIDENCE ONLY. DO NOT SHIP.
 * ==========================================================================
 * P0 ARRAY wire-text decoder spike for the planned read-only trino-doris
 * connector (PLAN sections 5 / G4 / 12). This is a strict recursive-descent
 * tokenizer for the Doris 4.1.3 MySQL-protocol ARRAY getString() wire grammar.
 *
 * It exists to PROVE feasibility (or prove ambiguity) of native ARRAY decoding
 * from Connector/J getString() text. It is deliberately strict: it FAILS LOUD
 * (throws DecodeException with byte offset) on anything outside the proven
 * grammar. It has NO permissive fallback.
 *
 * Observed grammar (Doris 4.1.3, Connector/J 9.7.0, getString()):
 *   array   := '[' (elem (', ' elem)*)? ']'
 *   elem    := 'null' | scalar | array           (element separator is COMMA+SPACE)
 *   scalar  := bare-token (numbers, booleans 1/0)         -- for numeric/bool/etc
 *            | '"' <RAW BYTES, NO ESCAPING> '"'           -- for string/date/datetime/ip
 *
 * CRITICAL escaping finding (byte-verified): inside a double-quoted element
 * Doris performs NO escaping. A '"' in a VARCHAR value appears on the wire as a
 * bare '"', a backslash as a bare '\', a real newline/tab/comma literally. This
 * makes quoted-string element decoding AMBIGUOUS (see report). The decoder here
 * treats a quoted element's closing quote as the FIRST '"' followed by ', ' or
 * ']' -- which is the only structural heuristic possible, and is provably wrong
 * for crafted inputs. For quoted "unambiguous" element types (DATE / DATETIME /
 * IPv4 / IPv6) whose value alphabet never contains '"', ',', '[' or ']', this
 * heuristic is exact. For VARCHAR/CHAR/STRING it is NOT safe -- the decoder
 * still parses, but the harness cross-checks against the SQL oracle to expose
 * the mismatches.
 */
public final class ArrayWireDecoder {

    public static final class DecodeException extends RuntimeException {
        public final int pos;
        public DecodeException(String msg, int pos) {
            super(msg + " (at wire offset " + pos + ")");
            this.pos = pos;
        }
    }

    /** Element categories the connector would care about. */
    public enum ElemKind {
        // bare (unquoted) tokens
        TINYINT, SMALLINT, INT, BIGINT, LARGEINT, FLOAT, DOUBLE, DECIMAL, BOOLEAN,
        // quoted, value alphabet excludes structural chars -> UNAMBIGUOUS
        DATE, DATETIME, IPV4, IPV6,
        // quoted, value alphabet may include structural chars -> AMBIGUOUS
        STRING,
        // recursive
        ARRAY
    }

    /** A decoded array node. null-valued elements are represented by DorisNull. */
    public static final class DorisNull {
        public static final DorisNull INSTANCE = new DorisNull();
        private DorisNull() {}
        public String toString() { return "<null>"; }
    }

    private final String s;
    private final int n;
    private int i;
    private final ElemKind elem;         // element kind for THIS level
    private final ElemKind childElem;    // for ARRAY: the element kind one level down (may itself be ARRAY)
    private final ArrayWireDecoder childDecoderTemplate;

    private ArrayWireDecoder(String s, ElemKind elem, ElemKind childElem) {
        this.s = s; this.n = s.length(); this.i = 0;
        this.elem = elem; this.childElem = childElem;
        this.childDecoderTemplate = null;
    }

    /**
     * Decode a top-level array wire text with the given element kind.
     * For nested arrays, pass a chain describing each level, outermost first.
     * @param wire   getString() value (must NOT be null; NULL array handled by caller)
     * @param kinds  element-kind chain: kinds[0] is this array's element kind;
     *               if kinds[0]==ARRAY then kinds[1] is the sub-element kind, etc.
     */
    public static List<Object> decode(String wire, ElemKind... kinds) {
        if (wire == null) throw new IllegalArgumentException("NULL array is not wire text; caller must handle SQL NULL");
        Parser p = new Parser(wire, kinds, 0);
        List<Object> out = p.parseArray();
        p.expectEnd();
        return out;
    }

    /** Recursive-descent parser. */
    static final class Parser {
        final String s; final int n; int i;
        final ElemKind[] kinds; final int depth;
        Parser(String s, ElemKind[] kinds, int depth) {
            this.s = s; this.n = s.length(); this.i = 0; this.kinds = kinds; this.depth = depth;
        }
        // sub-parser sharing the same buffer/offset
        Parser(String s, int i, ElemKind[] kinds, int depth) {
            this.s = s; this.n = s.length(); this.i = i; this.kinds = kinds; this.depth = depth;
        }

        void expectEnd() {
            if (i != n) throw new DecodeException("trailing garbage after array: '" + s.substring(i) + "'", i);
        }

        ElemKind myElemKind() {
            if (depth >= kinds.length) throw new DecodeException("nesting deeper than declared element-kind chain", i);
            return kinds[depth];
        }

        List<Object> parseArray() {
            if (i >= n || s.charAt(i) != '[')
                throw new DecodeException("expected '[' to open array", i);
            i++; // consume '['
            List<Object> out = new ArrayList<>();
            if (i < n && s.charAt(i) == ']') { i++; return out; } // empty []
            while (true) {
                out.add(parseElement());
                if (i >= n) throw new DecodeException("unterminated array (EOF before ']')", i);
                char c = s.charAt(i);
                if (c == ']') { i++; break; }
                // element separator MUST be exactly ", "
                if (c == ',') {
                    if (i + 1 >= n || s.charAt(i + 1) != ' ')
                        throw new DecodeException("separator ',' not followed by space", i);
                    i += 2;
                    continue;
                }
                throw new DecodeException("expected ', ' or ']' after element, saw '" + c + "'", i);
            }
            return out;
        }

        Object parseElement() {
            ElemKind k = myElemKind();
            // bare null token (lowercase, per byte-verified wire truth)
            if (starts("null")) {
                // Guard: a bare null must be bounded by a separator or ']'
                int after = i + 4;
                if (after == n || s.charAt(after) == ',' || s.charAt(after) == ']') {
                    i = after; return DorisNull.INSTANCE;
                }
                // 'null' as prefix of a bare token would be malformed for our kinds
                throw new DecodeException("bare token starting with 'null' is not a valid element", i);
            }
            switch (k) {
                case ARRAY: {
                    Parser sub = new Parser(s, i, kinds, depth + 1);
                    List<Object> nested = sub.parseArray();
                    this.i = sub.i;
                    return nested;
                }
                case DATE: case DATETIME: case IPV4: case IPV6: case STRING:
                    return parseQuoted(k);
                default:
                    return parseBare(k);
            }
        }

        boolean starts(String tok) {
            return s.regionMatches(i, tok, 0, tok.length());
        }

        /** Bare (unquoted) token: read up to the next ', ' or ']'. */
        Object parseBare(ElemKind k) {
            int start = i;
            while (i < n) {
                char c = s.charAt(i);
                if (c == ']' ) break;
                if (c == ',') break;
                i++;
            }
            String tok = s.substring(start, i);
            if (tok.isEmpty()) throw new DecodeException("empty bare token", start);
            return convertBare(k, tok, start);
        }

        Object convertBare(ElemKind k, String tok, int at) {
            try {
                switch (k) {
                    case TINYINT: { int v = Integer.parseInt(tok);
                        if (v < -128 || v > 127) throw new DecodeException("TINYINT out of range: " + tok, at);
                        return (byte) v; }
                    case SMALLINT: { int v = Integer.parseInt(tok);
                        if (v < -32768 || v > 32767) throw new DecodeException("SMALLINT out of range: " + tok, at);
                        return (short) v; }
                    case INT: return Integer.parseInt(tok);
                    case BIGINT: return Long.parseLong(tok);
                    case LARGEINT: return new BigInteger(tok); // exact 128-bit, no double
                    case FLOAT: return parseFloatStrict(tok, at);
                    case DOUBLE: return parseDoubleStrict(tok, at);
                    case DECIMAL: return new BigDecimal(tok); // exact scale, trailing zeros preserved
                    case BOOLEAN:
                        if (tok.equals("1")) return Boolean.TRUE;
                        if (tok.equals("0")) return Boolean.FALSE;
                        throw new DecodeException("BOOLEAN element not 1/0: '" + tok + "'", at);
                    default: throw new DecodeException("kind " + k + " is not a bare token", at);
                }
            } catch (NumberFormatException e) {
                throw new DecodeException("malformed " + k + " token '" + tok + "': " + e.getMessage(), at);
            }
        }

        Float parseFloatStrict(String tok, int at) {
            if (tok.equals("Infinity")) return Float.POSITIVE_INFINITY;
            if (tok.equals("-Infinity")) return Float.NEGATIVE_INFINITY;
            if (tok.equals("NaN")) return Float.NaN;
            return Float.parseFloat(tok);
        }
        Double parseDoubleStrict(String tok, int at) {
            if (tok.equals("Infinity")) return Double.POSITIVE_INFINITY;
            if (tok.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
            if (tok.equals("NaN")) return Double.NaN;
            return Double.parseDouble(tok);
        }

        /**
         * Quoted element. Opening '"' required. Because Doris does NOT escape the
         * content, the ONLY structural way to find the close is: the first '"'
         * that is immediately followed by ", " or "]" (end of element/array).
         * This is exact for DATE/DATETIME/IPv4/IPv6 (their alphabets never contain
         * '"' or ", " or "]"), and heuristic-only for STRING.
         */
        Object parseQuoted(ElemKind k) {
            if (s.charAt(i) != '"') throw new DecodeException("expected '\"' to open quoted element", i);
            int contentStart = i + 1;
            int j = contentStart;
            while (true) {
                if (j >= n) throw new DecodeException("unterminated quoted element (no closing '\"')", i);
                if (s.charAt(j) == '"') {
                    // Is this a structural close? followed by ", " or "]" or EOF-of-buffer-']'
                    int after = j + 1;
                    boolean structural =
                        (after >= n) ||
                        (s.charAt(after) == ']') ||
                        (s.charAt(after) == ',' && after + 1 < n && s.charAt(after + 1) == ' ');
                    if (structural) break;
                    // else: an interior '"' (only possible in STRING). Keep scanning.
                    if (k != ElemKind.STRING) {
                        throw new DecodeException("interior '\"' in a " + k + " element (alphabet violation)", j);
                    }
                }
                j++;
            }
            String raw = s.substring(contentStart, j);
            i = j + 1; // consume closing quote
            return convertQuoted(k, raw, contentStart);
        }

        Object convertQuoted(ElemKind k, String raw, int at) {
            switch (k) {
                case STRING: case IPV4: case IPV6: return raw; // canonical text
                case DATE: return LocalDate.parse(raw, DateTimeFormatter.ofPattern("uuuu-MM-dd"));
                case DATETIME: return parseDateTime(raw, at);
                default: throw new DecodeException("kind " + k + " is not a quoted element", at);
            }
        }

        LocalDateTime parseDateTime(String raw, int at) {
            // Doris renders "uuuu-MM-dd HH:mm:ss[.SSSSSS]" (space separator, optional frac)
            String pat = raw.length() > 19 ? "uuuu-MM-dd HH:mm:ss.SSSSSS" : "uuuu-MM-dd HH:mm:ss";
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(pat));
            } catch (Exception e) {
                throw new DecodeException("malformed DATETIME '" + raw + "': " + e.getMessage(), at);
            }
        }
    }

    private ArrayWireDecoder() { throw new AssertionError(); }
}

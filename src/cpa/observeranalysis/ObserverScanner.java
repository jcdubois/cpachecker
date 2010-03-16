/* The following code was generated by JFlex 1.4.3 on 15.03.10 17:19 */

package cpa.observeranalysis;

import java_cup.runtime.*;
@SuppressWarnings(value = { "all" })

/**
 * This class is a scanner generated by 
 * <a href="http://www.jflex.de/">JFlex</a> 1.4.3
 * on 15.03.10 17:19 from the specification file
 * <tt>D:/Meine Dateien/Uni/Vorlesungen 0910/Software Analyse/CPA/CPAchecker/src/cpa/observeranalysis/Scanner.jflex</tt>
 */
class ObserverScanner implements java_cup.runtime.Scanner, ObserverSym {

  /** This character denotes the end of file */
  public static final int YYEOF = -1;

  /** initial size of the lookahead buffer */
  private static final int ZZ_BUFFERSIZE = 16384;

  /** lexical states */
  public static final int STRING = 2;
  public static final int YYINITIAL = 0;
  public static final int SQUAREEXPR = 6;
  public static final int CURLYEXPR = 4;

  /**
   * ZZ_LEXSTATE[l] is the state in the DFA for the lexical state l
   * ZZ_LEXSTATE[l+1] is the state in the DFA for the lexical state l
   *                  at the beginning of a line
   * l is of the form l = 2*k, k a non negative integer
   */
  private static final int ZZ_LEXSTATE[] = { 
     0,  0,  1,  1,  2,  2,  3, 3
  };

  /** 
   * Translates characters to character classes
   */
  private static final String ZZ_CMAP_PACKED = 
    "\11\7\1\3\1\2\1\0\1\3\1\1\16\7\4\0\1\3\1\56"+
    "\1\52\1\0\1\6\3\0\1\14\1\15\1\5\1\57\1\0\1\16"+
    "\1\0\1\4\1\10\11\11\1\13\1\12\1\0\1\55\1\17\2\0"+
    "\1\20\1\6\1\27\1\36\1\32\1\50\1\37\1\34\1\30\1\6"+
    "\1\35\1\26\1\24\1\25\1\23\1\51\1\6\1\33\1\31\1\22"+
    "\1\21\5\6\1\54\1\60\1\63\1\0\1\6\1\0\1\45\3\6"+
    "\1\43\1\44\5\6\1\46\1\6\1\61\3\6\1\41\1\47\1\40"+
    "\1\42\5\6\1\53\1\0\1\62\1\0\41\7\2\0\4\6\4\0"+
    "\1\6\2\0\1\7\7\0\1\6\4\0\1\6\5\0\27\6\1\0"+
    "\37\6\1\0\u013f\6\31\0\162\6\4\0\14\6\16\0\5\6\11\0"+
    "\1\6\21\0\130\7\5\0\23\7\12\0\1\6\13\0\1\6\1\0"+
    "\3\6\1\0\1\6\1\0\24\6\1\0\54\6\1\0\46\6\1\0"+
    "\5\6\4\0\202\6\1\0\4\7\3\0\105\6\1\0\46\6\2\0"+
    "\2\6\6\0\20\6\41\0\46\6\2\0\1\6\7\0\47\6\11\0"+
    "\21\7\1\0\27\7\1\0\3\7\1\0\1\7\1\0\2\7\1\0"+
    "\1\7\13\0\33\6\5\0\3\6\15\0\4\7\14\0\6\7\13\0"+
    "\32\6\5\0\13\6\16\7\7\0\12\7\4\0\2\6\1\7\143\6"+
    "\1\0\1\6\10\7\1\0\6\7\2\6\2\7\1\0\4\7\2\6"+
    "\12\7\3\6\2\0\1\6\17\0\1\7\1\6\1\7\36\6\33\7"+
    "\2\0\3\6\60\0\46\6\13\7\1\6\u014f\0\3\7\66\6\2\0"+
    "\1\7\1\6\20\7\2\0\1\6\4\7\3\0\12\6\2\7\2\0"+
    "\12\7\21\0\3\7\1\0\10\6\2\0\2\6\2\0\26\6\1\0"+
    "\7\6\1\0\1\6\3\0\4\6\2\0\1\7\1\6\7\7\2\0"+
    "\2\7\2\0\3\7\11\0\1\7\4\0\2\6\1\0\3\6\2\7"+
    "\2\0\12\7\4\6\15\0\3\7\1\0\6\6\4\0\2\6\2\0"+
    "\26\6\1\0\7\6\1\0\2\6\1\0\2\6\1\0\2\6\2\0"+
    "\1\7\1\0\5\7\4\0\2\7\2\0\3\7\13\0\4\6\1\0"+
    "\1\6\7\0\14\7\3\6\14\0\3\7\1\0\11\6\1\0\3\6"+
    "\1\0\26\6\1\0\7\6\1\0\2\6\1\0\5\6\2\0\1\7"+
    "\1\6\10\7\1\0\3\7\1\0\3\7\2\0\1\6\17\0\2\6"+
    "\2\7\2\0\12\7\1\0\1\6\17\0\3\7\1\0\10\6\2\0"+
    "\2\6\2\0\26\6\1\0\7\6\1\0\2\6\1\0\5\6\2\0"+
    "\1\7\1\6\6\7\3\0\2\7\2\0\3\7\10\0\2\7\4\0"+
    "\2\6\1\0\3\6\4\0\12\7\1\0\1\6\20\0\1\7\1\6"+
    "\1\0\6\6\3\0\3\6\1\0\4\6\3\0\2\6\1\0\1\6"+
    "\1\0\2\6\3\0\2\6\3\0\3\6\3\0\10\6\1\0\3\6"+
    "\4\0\5\7\3\0\3\7\1\0\4\7\11\0\1\7\17\0\11\7"+
    "\11\0\1\6\7\0\3\7\1\0\10\6\1\0\3\6\1\0\27\6"+
    "\1\0\12\6\1\0\5\6\4\0\7\7\1\0\3\7\1\0\4\7"+
    "\7\0\2\7\11\0\2\6\4\0\12\7\22\0\2\7\1\0\10\6"+
    "\1\0\3\6\1\0\27\6\1\0\12\6\1\0\5\6\2\0\1\7"+
    "\1\6\7\7\1\0\3\7\1\0\4\7\7\0\2\7\7\0\1\6"+
    "\1\0\2\6\4\0\12\7\22\0\2\7\1\0\10\6\1\0\3\6"+
    "\1\0\27\6\1\0\20\6\4\0\6\7\2\0\3\7\1\0\4\7"+
    "\11\0\1\7\10\0\2\6\4\0\12\7\22\0\2\7\1\0\22\6"+
    "\3\0\30\6\1\0\11\6\1\0\1\6\2\0\7\6\3\0\1\7"+
    "\4\0\6\7\1\0\1\7\1\0\10\7\22\0\2\7\15\0\60\6"+
    "\1\7\2\6\7\7\4\0\10\6\10\7\1\0\12\7\47\0\2\6"+
    "\1\0\1\6\2\0\2\6\1\0\1\6\2\0\1\6\6\0\4\6"+
    "\1\0\7\6\1\0\3\6\1\0\1\6\1\0\1\6\2\0\2\6"+
    "\1\0\4\6\1\7\2\6\6\7\1\0\2\7\1\6\2\0\5\6"+
    "\1\0\1\6\1\0\6\7\2\0\12\7\2\0\2\6\42\0\1\6"+
    "\27\0\2\7\6\0\12\7\13\0\1\7\1\0\1\7\1\0\1\7"+
    "\4\0\2\7\10\6\1\0\42\6\6\0\24\7\1\0\2\7\4\6"+
    "\4\0\10\7\1\0\44\7\11\0\1\7\71\0\42\6\1\0\5\6"+
    "\1\0\2\6\1\0\7\7\3\0\4\7\6\0\12\7\6\0\6\6"+
    "\4\7\106\0\46\6\12\0\51\6\7\0\132\6\5\0\104\6\5\0"+
    "\122\6\6\0\7\6\1\0\77\6\1\0\1\6\1\0\4\6\2\0"+
    "\7\6\1\0\1\6\1\0\4\6\2\0\47\6\1\0\1\6\1\0"+
    "\4\6\2\0\37\6\1\0\1\6\1\0\4\6\2\0\7\6\1\0"+
    "\1\6\1\0\4\6\2\0\7\6\1\0\7\6\1\0\27\6\1\0"+
    "\37\6\1\0\1\6\1\0\4\6\2\0\7\6\1\0\47\6\1\0"+
    "\23\6\16\0\11\7\56\0\125\6\14\0\u026c\6\2\0\10\6\12\0"+
    "\32\6\5\0\113\6\3\0\3\6\17\0\15\6\1\0\4\6\3\7"+
    "\13\0\22\6\3\7\13\0\22\6\2\7\14\0\15\6\1\0\3\6"+
    "\1\0\2\7\14\0\64\6\40\7\3\0\1\6\3\0\2\6\1\7"+
    "\2\0\12\7\41\0\3\7\2\0\12\7\6\0\130\6\10\0\51\6"+
    "\1\7\126\0\35\6\3\0\14\7\4\0\14\7\12\0\12\7\36\6"+
    "\2\0\5\6\u038b\0\154\6\224\0\234\6\4\0\132\6\6\0\26\6"+
    "\2\0\6\6\2\0\46\6\2\0\6\6\2\0\10\6\1\0\1\6"+
    "\1\0\1\6\1\0\1\6\1\0\37\6\2\0\65\6\1\0\7\6"+
    "\1\0\1\6\3\0\3\6\1\0\7\6\3\0\4\6\2\0\6\6"+
    "\4\0\15\6\5\0\3\6\1\0\7\6\17\0\4\7\32\0\5\7"+
    "\20\0\2\6\23\0\1\6\13\0\4\7\6\0\6\7\1\0\1\6"+
    "\15\0\1\6\40\0\22\6\36\0\15\7\4\0\1\7\3\0\6\7"+
    "\27\0\1\6\4\0\1\6\2\0\12\6\1\0\1\6\3\0\5\6"+
    "\6\0\1\6\1\0\1\6\1\0\1\6\1\0\4\6\1\0\3\6"+
    "\1\0\7\6\3\0\3\6\5\0\5\6\26\0\44\6\u0e81\0\3\6"+
    "\31\0\11\6\6\7\1\0\5\6\2\0\5\6\4\0\126\6\2\0"+
    "\2\7\2\0\3\6\1\0\137\6\5\0\50\6\4\0\136\6\21\0"+
    "\30\6\70\0\20\6\u0200\0\u19b6\6\112\0\u51a6\6\132\0\u048d\6\u0773\0"+
    "\u2ba4\6\u215c\0\u012e\6\2\0\73\6\225\0\7\6\14\0\5\6\5\0"+
    "\1\6\1\7\12\6\1\0\15\6\1\0\5\6\1\0\1\6\1\0"+
    "\2\6\1\0\2\6\1\0\154\6\41\0\u016b\6\22\0\100\6\2\0"+
    "\66\6\50\0\15\6\3\0\20\7\20\0\4\7\17\0\2\6\30\0"+
    "\3\6\31\0\1\6\6\0\5\6\1\0\207\6\2\0\1\7\4\0"+
    "\1\6\13\0\12\7\7\0\32\6\4\0\1\6\1\0\32\6\12\0"+
    "\132\6\3\0\6\6\2\0\6\6\2\0\6\6\2\0\3\6\3\0"+
    "\2\6\3\0\2\6\22\0\3\7\4\0";

  /** 
   * Translates characters to character classes
   */
  private static final char [] ZZ_CMAP = zzUnpackCMap(ZZ_CMAP_PACKED);

  /** 
   * Translates DFA states to action switch labels.
   */
  private static final int [] ZZ_ACTION = zzUnpackAction();

  private static final String ZZ_ACTION_PACKED_0 =
    "\4\0\1\1\2\2\1\1\1\3\2\4\1\5\1\6"+
    "\1\7\1\10\1\11\15\3\1\12\1\13\1\14\1\15"+
    "\1\1\1\16\1\17\1\20\1\21\1\17\1\22\1\17"+
    "\1\23\2\0\1\24\10\3\1\25\5\3\1\26\1\27"+
    "\1\30\1\31\1\32\1\33\2\0\15\3\1\0\2\3"+
    "\1\34\5\3\1\35\5\3\1\36\1\37\1\40\1\3"+
    "\1\41\1\42\1\43\1\3\1\44\2\3\1\45\1\3"+
    "\1\46";

  private static int [] zzUnpackAction() {
    int [] result = new int[109];
    int offset = 0;
    offset = zzUnpackAction(ZZ_ACTION_PACKED_0, offset, result);
    return result;
  }

  private static int zzUnpackAction(String packed, int offset, int [] result) {
    int i = 0;       /* index in packed string  */
    int j = offset;  /* index in unpacked array */
    int l = packed.length();
    while (i < l) {
      int count = packed.charAt(i++);
      int value = packed.charAt(i++);
      do result[j++] = value; while (--count > 0);
    }
    return j;
  }


  /** 
   * Translates a state to a row index in the transition table
   */
  private static final int [] ZZ_ROWMAP = zzUnpackRowMap();

  private static final String ZZ_ROWMAP_PACKED_0 =
    "\0\0\0\64\0\150\0\234\0\320\0\u0104\0\320\0\u0138"+
    "\0\u016c\0\320\0\u01a0\0\320\0\320\0\320\0\320\0\u01d4"+
    "\0\u0208\0\u023c\0\u0270\0\u02a4\0\u02d8\0\u030c\0\u0340\0\u0374"+
    "\0\u03a8\0\u03dc\0\u0410\0\u0444\0\u0478\0\320\0\320\0\320"+
    "\0\u04ac\0\u04e0\0\320\0\u0514\0\320\0\u0548\0\u057c\0\320"+
    "\0\u05b0\0\320\0\u05e4\0\u0618\0\320\0\u064c\0\u0680\0\u06b4"+
    "\0\u06e8\0\u071c\0\u0750\0\u0784\0\u07b8\0\u016c\0\u07ec\0\u0820"+
    "\0\u0854\0\u0888\0\u08bc\0\320\0\320\0\320\0\320\0\320"+
    "\0\320\0\u08f0\0\u0924\0\u0958\0\u098c\0\u09c0\0\u09f4\0\u0a28"+
    "\0\u0a5c\0\u0a90\0\u0ac4\0\u0af8\0\u0b2c\0\u0b60\0\u0b94\0\u0bc8"+
    "\0\u0bfc\0\u0c30\0\u0c64\0\u016c\0\u0c98\0\u0ccc\0\u0d00\0\u0d34"+
    "\0\u0d68\0\u016c\0\u0d9c\0\u0dd0\0\u0e04\0\u0e38\0\u0e6c\0\u016c"+
    "\0\u016c\0\u016c\0\u0ea0\0\u016c\0\u016c\0\u016c\0\u0ed4\0\u016c"+
    "\0\u0f08\0\u0f3c\0\u016c\0\u0f70\0\u016c";

  private static int [] zzUnpackRowMap() {
    int [] result = new int[109];
    int offset = 0;
    offset = zzUnpackRowMap(ZZ_ROWMAP_PACKED_0, offset, result);
    return result;
  }

  private static int zzUnpackRowMap(String packed, int offset, int [] result) {
    int i = 0;  /* index in packed string  */
    int j = offset;  /* index in unpacked array */
    int l = packed.length();
    while (i < l) {
      int high = packed.charAt(i++) << 16;
      result[j++] = high | packed.charAt(i++);
    }
    return j;
  }

  /** 
   * The transition table of the DFA
   */
  private static final int [] ZZ_TRANS = zzUnpackTrans();

  private static final String ZZ_TRANS_PACKED_0 =
    "\1\5\1\6\2\7\1\10\1\5\1\11\1\5\1\12"+
    "\1\13\1\14\1\15\1\16\1\17\1\20\1\5\1\21"+
    "\1\11\1\22\1\11\1\23\1\11\1\24\1\25\1\26"+
    "\1\27\4\11\1\30\1\31\1\32\3\11\1\33\3\11"+
    "\1\34\1\35\1\36\1\37\1\40\1\41\1\42\1\43"+
    "\1\5\1\11\2\5\1\44\2\5\47\44\1\45\5\44"+
    "\1\46\3\44\1\47\2\5\55\47\1\46\1\47\1\50"+
    "\1\47\1\51\2\5\55\51\1\46\2\51\1\52\66\0"+
    "\1\7\65\0\1\53\1\54\64\0\4\11\6\0\32\11"+
    "\7\0\1\11\12\0\2\13\71\0\1\55\52\0\4\11"+
    "\6\0\1\11\1\56\7\11\1\57\20\11\7\0\1\11"+
    "\10\0\4\11\6\0\13\11\1\60\16\11\7\0\1\11"+
    "\10\0\4\11\6\0\1\61\31\11\7\0\1\11\10\0"+
    "\4\11\6\0\3\11\1\62\26\11\7\0\1\11\10\0"+
    "\4\11\6\0\14\11\1\63\15\11\7\0\1\11\10\0"+
    "\4\11\6\0\5\11\1\64\24\11\7\0\1\11\10\0"+
    "\4\11\6\0\2\11\1\65\27\11\7\0\1\11\10\0"+
    "\4\11\6\0\3\11\1\66\26\11\7\0\1\11\10\0"+
    "\4\11\6\0\3\11\1\67\26\11\7\0\1\11\10\0"+
    "\4\11\6\0\21\11\1\70\10\11\7\0\1\11\10\0"+
    "\4\11\6\0\25\11\1\71\4\11\7\0\1\11\10\0"+
    "\4\11\6\0\1\72\31\11\7\0\1\11\10\0\4\11"+
    "\6\0\13\11\1\73\16\11\7\0\1\11\57\0\1\74"+
    "\63\0\1\75\6\0\1\44\2\0\47\44\1\0\5\44"+
    "\1\0\3\44\40\0\1\76\1\77\10\0\1\100\6\0"+
    "\1\101\2\0\1\47\2\0\55\47\1\0\1\47\1\0"+
    "\1\47\1\51\2\0\55\51\1\0\2\51\1\0\1\53"+
    "\1\6\1\7\61\53\5\102\1\103\56\102\6\0\4\11"+
    "\6\0\2\11\1\104\27\11\7\0\1\11\10\0\4\11"+
    "\6\0\11\11\1\105\20\11\7\0\1\11\10\0\4\11"+
    "\6\0\1\11\1\106\30\11\7\0\1\11\10\0\4\11"+
    "\6\0\2\11\1\107\27\11\7\0\1\11\10\0\4\11"+
    "\6\0\7\11\1\110\22\11\7\0\1\11\10\0\4\11"+
    "\6\0\12\11\1\111\17\11\7\0\1\11\10\0\4\11"+
    "\6\0\10\11\1\112\21\11\7\0\1\11\10\0\4\11"+
    "\6\0\1\113\31\11\7\0\1\11\10\0\4\11\6\0"+
    "\2\11\1\114\27\11\7\0\1\11\10\0\4\11\6\0"+
    "\22\11\1\115\7\11\7\0\1\11\10\0\4\11\6\0"+
    "\26\11\1\116\3\11\7\0\1\11\10\0\4\11\6\0"+
    "\6\11\1\117\23\11\7\0\1\11\10\0\4\11\6\0"+
    "\10\11\1\120\21\11\7\0\1\11\2\0\5\102\1\121"+
    "\56\102\4\0\1\7\1\103\64\0\4\11\6\0\3\11"+
    "\1\122\26\11\7\0\1\11\10\0\4\11\6\0\12\11"+
    "\1\123\17\11\7\0\1\11\10\0\4\11\6\0\12\11"+
    "\1\124\17\11\7\0\1\11\10\0\4\11\6\0\7\11"+
    "\1\125\22\11\7\0\1\11\10\0\4\11\6\0\1\126"+
    "\31\11\7\0\1\11\10\0\4\11\6\0\7\11\1\127"+
    "\22\11\7\0\1\11\10\0\4\11\6\0\2\11\1\130"+
    "\27\11\7\0\1\11\10\0\4\11\6\0\2\11\1\131"+
    "\27\11\7\0\1\11\10\0\4\11\6\0\3\11\1\132"+
    "\26\11\7\0\1\11\10\0\4\11\6\0\23\11\1\124"+
    "\6\11\7\0\1\11\10\0\4\11\6\0\27\11\1\133"+
    "\2\11\7\0\1\11\10\0\4\11\6\0\11\11\1\134"+
    "\20\11\7\0\1\11\10\0\4\11\6\0\5\11\1\135"+
    "\24\11\7\0\1\11\2\0\4\102\1\7\1\121\56\102"+
    "\6\0\4\11\6\0\4\11\1\136\25\11\7\0\1\11"+
    "\10\0\4\11\6\0\13\11\1\137\16\11\7\0\1\11"+
    "\10\0\4\11\6\0\14\11\1\140\15\11\7\0\1\11"+
    "\10\0\4\11\6\0\6\11\1\141\23\11\7\0\1\11"+
    "\10\0\4\11\6\0\15\11\1\142\14\11\7\0\1\11"+
    "\10\0\4\11\6\0\10\11\1\143\21\11\7\0\1\11"+
    "\10\0\4\11\6\0\12\11\1\144\17\11\7\0\1\11"+
    "\10\0\4\11\6\0\23\11\1\145\6\11\7\0\1\11"+
    "\10\0\4\11\6\0\12\11\1\145\17\11\7\0\1\11"+
    "\10\0\4\11\6\0\2\11\1\146\27\11\7\0\1\11"+
    "\10\0\4\11\6\0\1\147\31\11\7\0\1\11\10\0"+
    "\4\11\6\0\2\11\1\150\27\11\7\0\1\11\10\0"+
    "\4\11\6\0\1\151\31\11\7\0\1\11\10\0\4\11"+
    "\6\0\2\11\1\152\27\11\7\0\1\11\10\0\4\11"+
    "\6\0\6\11\1\153\23\11\7\0\1\11\10\0\4\11"+
    "\6\0\3\11\1\154\26\11\7\0\1\11\10\0\4\11"+
    "\6\0\5\11\1\155\24\11\7\0\1\11\2\0";

  private static int [] zzUnpackTrans() {
    int [] result = new int[4004];
    int offset = 0;
    offset = zzUnpackTrans(ZZ_TRANS_PACKED_0, offset, result);
    return result;
  }

  private static int zzUnpackTrans(String packed, int offset, int [] result) {
    int i = 0;       /* index in packed string  */
    int j = offset;  /* index in unpacked array */
    int l = packed.length();
    while (i < l) {
      int count = packed.charAt(i++);
      int value = packed.charAt(i++);
      value--;
      do result[j++] = value; while (--count > 0);
    }
    return j;
  }


  /* error codes */
  private static final int ZZ_UNKNOWN_ERROR = 0;
  private static final int ZZ_NO_MATCH = 1;
  private static final int ZZ_PUSHBACK_2BIG = 2;

  /* error messages for the codes above */
  private static final String ZZ_ERROR_MSG[] = {
    "Unkown internal scanner error",
    "Error: could not match input",
    "Error: pushback value was too large"
  };

  /**
   * ZZ_ATTRIBUTE[aState] contains the attributes of state <code>aState</code>
   */
  private static final int [] ZZ_ATTRIBUTE = zzUnpackAttribute();

  private static final String ZZ_ATTRIBUTE_PACKED_0 =
    "\4\0\1\11\1\1\1\11\2\1\1\11\1\1\4\11"+
    "\16\1\3\11\2\1\1\11\1\1\1\11\2\1\1\11"+
    "\1\1\1\11\2\0\1\11\16\1\6\11\2\0\15\1"+
    "\1\0\34\1";

  private static int [] zzUnpackAttribute() {
    int [] result = new int[109];
    int offset = 0;
    offset = zzUnpackAttribute(ZZ_ATTRIBUTE_PACKED_0, offset, result);
    return result;
  }

  private static int zzUnpackAttribute(String packed, int offset, int [] result) {
    int i = 0;       /* index in packed string  */
    int j = offset;  /* index in unpacked array */
    int l = packed.length();
    while (i < l) {
      int count = packed.charAt(i++);
      int value = packed.charAt(i++);
      do result[j++] = value; while (--count > 0);
    }
    return j;
  }

  /** the input device */
  private java.io.Reader zzReader;

  /** the current state of the DFA */
  private int zzState;

  /** the current lexical state */
  private int zzLexicalState = YYINITIAL;

  /** this buffer contains the current text to be matched and is
      the source of the yytext() string */
  private char zzBuffer[] = new char[ZZ_BUFFERSIZE];

  /** the textposition at the last accepting state */
  private int zzMarkedPos;

  /** the current text position in the buffer */
  private int zzCurrentPos;

  /** startRead marks the beginning of the yytext() string in the buffer */
  private int zzStartRead;

  /** endRead marks the last character in the buffer, that has been read
      from input */
  private int zzEndRead;

  /** number of newlines encountered up to the start of the matched text */
  private int yyline;

  /** the number of characters up to the start of the matched text */
  private int yychar;

  /**
   * the number of characters from the last newline up to the start of the 
   * matched text
   */
  private int yycolumn;

  /** 
   * zzAtBOL == true <=> the scanner is currently at the beginning of a line
   */
  private boolean zzAtBOL = true;

  /** zzAtEOF == true <=> the scanner is at the EOF */
  private boolean zzAtEOF;

  /** denotes if the user-EOF-code has already been executed */
  private boolean zzEOFDone;

  /* user code: */
  private StringBuilder string = new StringBuilder();
  private SymbolFactory sf;

   public ObserverScanner(java.io.InputStream r, SymbolFactory sf){
	this(r);
	this.sf = sf;
  }
  public int getLine() {
     return this.yyline;
   }
   public int getColumn() {
     return this.yycolumn;
   }
  
  private Symbol symbol(String name, int sym) {
    return  sf.newSymbol(name, sym);
  }
  private Symbol symbol(String name, int sym, Object val) {
    return  sf.newSymbol(name, sym, val);
  }
  
  private void error(String message) {
    System.out.println("Error at line "+(yyline+1)+", column "+(yycolumn+1)+" : "+message);
  }


  /**
   * Creates a new scanner
   * There is also a java.io.InputStream version of this constructor.
   *
   * @param   in  the java.io.Reader to read input from.
   */
  ObserverScanner(java.io.Reader in) {
    this.zzReader = in;
  }

  /**
   * Creates a new scanner.
   * There is also java.io.Reader version of this constructor.
   *
   * @param   in  the java.io.Inputstream to read input from.
   */
  ObserverScanner(java.io.InputStream in) {
    this(new java.io.InputStreamReader(in));
  }

  /** 
   * Unpacks the compressed character translation table.
   *
   * @param packed   the packed character translation table
   * @return         the unpacked character translation table
   */
  private static char [] zzUnpackCMap(String packed) {
    char [] map = new char[0x10000];
    int i = 0;  /* index in packed string  */
    int j = 0;  /* index in unpacked array */
    while (i < 1772) {
      int  count = packed.charAt(i++);
      char value = packed.charAt(i++);
      do map[j++] = value; while (--count > 0);
    }
    return map;
  }


  /**
   * Refills the input buffer.
   *
   * @return      <code>false</code>, iff there was new input.
   * 
   * @exception   java.io.IOException  if any I/O-Error occurs
   */
  private boolean zzRefill() throws java.io.IOException {

    /* first: make room (if you can) */
    if (zzStartRead > 0) {
      System.arraycopy(zzBuffer, zzStartRead,
                       zzBuffer, 0,
                       zzEndRead-zzStartRead);

      /* translate stored positions */
      zzEndRead-= zzStartRead;
      zzCurrentPos-= zzStartRead;
      zzMarkedPos-= zzStartRead;
      zzStartRead = 0;
    }

    /* is the buffer big enough? */
    if (zzCurrentPos >= zzBuffer.length) {
      /* if not: blow it up */
      char newBuffer[] = new char[zzCurrentPos*2];
      System.arraycopy(zzBuffer, 0, newBuffer, 0, zzBuffer.length);
      zzBuffer = newBuffer;
    }

    /* finally: fill the buffer with new input */
    int numRead = zzReader.read(zzBuffer, zzEndRead,
                                            zzBuffer.length-zzEndRead);

    if (numRead > 0) {
      zzEndRead+= numRead;
      return false;
    }
    // unlikely but not impossible: read 0 characters, but not at end of stream    
    if (numRead == 0) {
      int c = zzReader.read();
      if (c == -1) {
        return true;
      } else {
        zzBuffer[zzEndRead++] = (char) c;
        return false;
      }     
    }

	// numRead < 0
    return true;
  }

    
  /**
   * Closes the input stream.
   */
  public final void yyclose() throws java.io.IOException {
    zzAtEOF = true;            /* indicate end of file */
    zzEndRead = zzStartRead;  /* invalidate buffer    */

    if (zzReader != null)
      zzReader.close();
  }


  /**
   * Resets the scanner to read from a new input stream.
   * Does not close the old reader.
   *
   * All internal variables are reset, the old input stream 
   * <b>cannot</b> be reused (internal buffer is discarded and lost).
   * Lexical state is set to <tt>ZZ_INITIAL</tt>.
   *
   * @param reader   the new input stream 
   */
  public final void yyreset(java.io.Reader reader) {
    zzReader = reader;
    zzAtBOL  = true;
    zzAtEOF  = false;
    zzEOFDone = false;
    zzEndRead = zzStartRead = 0;
    zzCurrentPos = zzMarkedPos = 0;
    yyline = yychar = yycolumn = 0;
    zzLexicalState = YYINITIAL;
  }


  /**
   * Returns the current lexical state.
   */
  public final int yystate() {
    return zzLexicalState;
  }


  /**
   * Enters a new lexical state
   *
   * @param newState the new lexical state
   */
  public final void yybegin(int newState) {
    zzLexicalState = newState;
  }


  /**
   * Returns the text matched by the current regular expression.
   */
  public final String yytext() {
    return new String( zzBuffer, zzStartRead, zzMarkedPos-zzStartRead );
  }


  /**
   * Returns the character at position <tt>pos</tt> from the 
   * matched text. 
   * 
   * It is equivalent to yytext().charAt(pos), but faster
   *
   * @param pos the position of the character to fetch. 
   *            A value from 0 to yylength()-1.
   *
   * @return the character at position pos
   */
  public final char yycharat(int pos) {
    return zzBuffer[zzStartRead+pos];
  }


  /**
   * Returns the length of the matched text region.
   */
  public final int yylength() {
    return zzMarkedPos-zzStartRead;
  }


  /**
   * Reports an error that occured while scanning.
   *
   * In a wellformed scanner (no or only correct usage of 
   * yypushback(int) and a match-all fallback rule) this method 
   * will only be called with things that "Can't Possibly Happen".
   * If this method is called, something is seriously wrong
   * (e.g. a JFlex bug producing a faulty scanner etc.).
   *
   * Usual syntax/scanner level error handling should be done
   * in error fallback rules.
   *
   * @param   errorCode  the code of the errormessage to display
   */
  private void zzScanError(int errorCode) {
    String message;
    try {
      message = ZZ_ERROR_MSG[errorCode];
    }
    catch (ArrayIndexOutOfBoundsException e) {
      message = ZZ_ERROR_MSG[ZZ_UNKNOWN_ERROR];
    }

    throw new Error(message);
  } 


  /**
   * Pushes the specified amount of characters back into the input stream.
   *
   * They will be read again by then next call of the scanning method
   *
   * @param number  the number of characters to be read again.
   *                This number must not be greater than yylength()!
   */
  public void yypushback(int number)  {
    if ( number > yylength() )
      zzScanError(ZZ_PUSHBACK_2BIG);

    zzMarkedPos -= number;
  }


  /**
   * Contains user EOF-code, which will be executed exactly once,
   * when the end of file is reached
   */
  private void zzDoEOF() throws java.io.IOException {
    if (!zzEOFDone) {
      zzEOFDone = true;
      yyclose();
    }
  }


  /**
   * Resumes scanning until the next regular expression is matched,
   * the end of input is encountered or an I/O-Error occurs.
   *
   * @return      the next token
   * @exception   java.io.IOException  if any I/O-Error occurs
   */
  public java_cup.runtime.Symbol next_token() throws java.io.IOException {
    int zzInput;
    int zzAction;

    // cached fields:
    int zzCurrentPosL;
    int zzMarkedPosL;
    int zzEndReadL = zzEndRead;
    char [] zzBufferL = zzBuffer;
    char [] zzCMapL = ZZ_CMAP;

    int [] zzTransL = ZZ_TRANS;
    int [] zzRowMapL = ZZ_ROWMAP;
    int [] zzAttrL = ZZ_ATTRIBUTE;

    while (true) {
      zzMarkedPosL = zzMarkedPos;

      boolean zzR = false;
      for (zzCurrentPosL = zzStartRead; zzCurrentPosL < zzMarkedPosL;
                                                             zzCurrentPosL++) {
        switch (zzBufferL[zzCurrentPosL]) {
        case '\u000B':
        case '\u000C':
        case '\u0085':
        case '\u2028':
        case '\u2029':
          yyline++;
          yycolumn = 0;
          zzR = false;
          break;
        case '\r':
          yyline++;
          yycolumn = 0;
          zzR = true;
          break;
        case '\n':
          if (zzR)
            zzR = false;
          else {
            yyline++;
            yycolumn = 0;
          }
          break;
        default:
          zzR = false;
          yycolumn++;
        }
      }

      if (zzR) {
        // peek one character ahead if it is \n (if we have counted one line too much)
        boolean zzPeek;
        if (zzMarkedPosL < zzEndReadL)
          zzPeek = zzBufferL[zzMarkedPosL] == '\n';
        else if (zzAtEOF)
          zzPeek = false;
        else {
          boolean eof = zzRefill();
          zzEndReadL = zzEndRead;
          zzMarkedPosL = zzMarkedPos;
          zzBufferL = zzBuffer;
          if (eof) 
            zzPeek = false;
          else 
            zzPeek = zzBufferL[zzMarkedPosL] == '\n';
        }
        if (zzPeek) yyline--;
      }
      zzAction = -1;

      zzCurrentPosL = zzCurrentPos = zzStartRead = zzMarkedPosL;
  
      zzState = ZZ_LEXSTATE[zzLexicalState];


      zzForAction: {
        while (true) {
    
          if (zzCurrentPosL < zzEndReadL)
            zzInput = zzBufferL[zzCurrentPosL++];
          else if (zzAtEOF) {
            zzInput = YYEOF;
            break zzForAction;
          }
          else {
            // store back cached positions
            zzCurrentPos  = zzCurrentPosL;
            zzMarkedPos   = zzMarkedPosL;
            boolean eof = zzRefill();
            // get translated positions and possibly new buffer
            zzCurrentPosL  = zzCurrentPos;
            zzMarkedPosL   = zzMarkedPos;
            zzBufferL      = zzBuffer;
            zzEndReadL     = zzEndRead;
            if (eof) {
              zzInput = YYEOF;
              break zzForAction;
            }
            else {
              zzInput = zzBufferL[zzCurrentPosL++];
            }
          }
          int zzNext = zzTransL[ zzRowMapL[zzState] + zzCMapL[zzInput] ];
          if (zzNext == -1) break zzForAction;
          zzState = zzNext;

          int zzAttributes = zzAttrL[zzState];
          if ( (zzAttributes & 1) == 1 ) {
            zzAction = zzState;
            zzMarkedPosL = zzCurrentPosL;
            if ( (zzAttributes & 8) == 8 ) break zzForAction;
          }

        }
      }

      // store back cached position
      zzMarkedPos = zzMarkedPosL;

      switch (zzAction < 0 ? zzAction : ZZ_ACTION[zzAction]) {
        case 2: 
          { /* ignore */
          }
        case 39: break;
        case 29: 
          { return symbol("GOTO", ObserverSym.GOTO);
          }
        case 40: break;
        case 31: 
          { return symbol("LOCAL", ObserverSym.LOCAL);
          }
        case 41: break;
        case 8: 
          { return symbol(")", ObserverSym.CLOSE_BRACKETS);
          }
        case 42: break;
        case 24: 
          { string.append('\t');
          }
        case 43: break;
        case 30: 
          { return symbol("MATCH", ObserverSym.MATCH);
          }
        case 44: break;
        case 10: 
          { string.setLength(0); yybegin(STRING);
          }
        case 45: break;
        case 17: 
          { string.append('\\');
          }
        case 46: break;
        case 25: 
          { string.append('\r');
          }
        case 47: break;
        case 20: 
          { return symbol("->", ObserverSym.ARROW);
          }
        case 48: break;
        case 3: 
          { return symbol("ID", ObserverSym.IDENTIFIER, yytext());
          }
        case 49: break;
        case 6: 
          { return symbol(":", ObserverSym.COLON);
          }
        case 50: break;
        case 11: 
          { string.setLength(0); yybegin(CURLYEXPR);
          }
        case 51: break;
        case 32: 
          { return symbol("CHECK", ObserverSym.CHECK);
          }
        case 52: break;
        case 33: 
          { return symbol("STATE", ObserverSym.STATE);
          }
        case 53: break;
        case 5: 
          { return symbol(";", ObserverSym.SEMICOLON);
          }
        case 54: break;
        case 37: 
          { return symbol("INITIAL", ObserverSym.INITIAL);
          }
        case 55: break;
        case 35: 
          { return symbol("PRINT", ObserverSym.PRINT);
          }
        case 56: break;
        case 7: 
          { return symbol("(", ObserverSym.OPEN_BRACKETS);
          }
        case 57: break;
        case 36: 
          { return symbol("ASSERT", ObserverSym.ASS);
          }
        case 58: break;
        case 1: 
          { error("Fallback error"); throw new Error("Illegal character <"+
                                                    yytext()+">");
          }
        case 59: break;
        case 9: 
          { return symbol("-", ObserverSym.MINUS);
          }
        case 60: break;
        case 14: 
          { return symbol("+", ObserverSym.PLUS);
          }
        case 61: break;
        case 26: 
          { string.append('\"');
          }
        case 62: break;
        case 23: 
          { return symbol("!=", ObserverSym.NEQ);
          }
        case 63: break;
        case 38: 
          { return symbol("AUTOMATON", ObserverSym.AUTOMATON);
          }
        case 64: break;
        case 21: 
          { return symbol("DO", ObserverSym.DO);
          }
        case 65: break;
        case 22: 
          { return symbol("==", ObserverSym.EQEQ);
          }
        case 66: break;
        case 13: 
          { return symbol("=", ObserverSym.EQ);
          }
        case 67: break;
        case 12: 
          { string.setLength(0); yybegin(SQUAREEXPR);
          }
        case 68: break;
        case 27: 
          { string.append('\n');
          }
        case 69: break;
        case 28: 
          { return symbol("TRUE", ObserverSym.TRUE);
          }
        case 70: break;
        case 4: 
          { return symbol("INT", ObserverSym.INTEGER_LITERAL, yytext());
          }
        case 71: break;
        case 18: 
          { yybegin(YYINITIAL); 
                                   return symbol("CURLYEXPR", ObserverSym.CURLYEXPR, 
                                   string.toString());
          }
        case 72: break;
        case 34: 
          { return symbol("FALSE", ObserverSym.FALSE);
          }
        case 73: break;
        case 16: 
          { yybegin(YYINITIAL); 
                                   return symbol("STRING", ObserverSym.STRING_LITERAL, 
                                   string.toString());
          }
        case 74: break;
        case 19: 
          { yybegin(YYINITIAL); 
                                   return symbol("CURLYEXPR", ObserverSym.SQUAREEXPR, 
                                   string.toString());
          }
        case 75: break;
        case 15: 
          { string.append( yytext() );
          }
        case 76: break;
        default: 
          if (zzInput == YYEOF && zzStartRead == zzCurrentPos) {
            zzAtEOF = true;
            zzDoEOF();
              {     return symbol("EOF", ObserverSym.EOF);
 }
          } 
          else {
            zzScanError(ZZ_NO_MATCH);
          }
      }
    }
  }


}

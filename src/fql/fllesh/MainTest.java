package fql.fllesh;

import org.junit.Test;

public class MainTest {

  @Test
  public void testMain001() throws Exception {
    String[] lArguments = new String[2];
    
    lArguments[0] = "COVER STATES(@ENTRY(f))";
    lArguments[1] = "test/tests/single/functionCall.c";
    
    Main.main(lArguments);
  }
  
  @Test
  public void testMain002() throws Exception {
    String[] lArguments = new String[2];
    
    lArguments[0] = "COVER EDGES(@ENTRY(f))";
    lArguments[1] = "test/tests/single/functionCall.c";
    
    Main.main(lArguments);
  }

}

package dev.twme.editpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Rebuilding the whole chat line when the client only sends the text up to the cursor. */
class WithTailTest {
  private static final String FULL = "//set glowstone -pev 0";

  /** Simulates the server receiving each request in order; returns its reconstruction. */
  private static String run(String... sent) {
    String typed = null;
    for (String s : sent) typed = PevFlag.withTail(typed, s);
    return typed;
  }

  @Test
  void cursorAtEnd() {
    assertEquals(FULL, run("//set glowstone -pev", "//set glowstone -pev ", FULL));
  }

  @Test
  void moveLeftKeepsTheLine() {
    assertEquals(FULL, run(FULL, "//set glowstone -pev", "//set glowstone -pe", "//set glowstone -p", "//set glowstone -",
        "//set glowstone ", "//set glowstone"));
  }

  @Test
  void moveLeftDeleteAndRetypeAnArgument() {
    assertEquals("//set gold_block -pev 0", run(FULL, "//set glowstone -pev", "//set glowstone ", "//set glowstone",
        "//set glowston", "//set glow", "//set g", "//set ", "//set g", "//set go", "//set gol", "//set gold",
        "//set gold_", "//set gold_b", "//set gold_bl", "//set gold_blo", "//set gold_bloc", "//set gold_block"));
  }

  @Test
  void outOfOrderOlderRequest() {
    assertEquals(FULL, run(FULL, "//set glowstone -pe", "//set glowstone -pev "));
  }

  @Test
  void editFirstOfTwoArguments() {
    assertEquals("//replace dirt gold_block -pev",
        run("//replace stone gold_block -pev", "//replace stone", "//replace ", "//replace d", "//replace di",
            "//replace dir", "//replace dirt"));
  }

  @Test
  void typingAtTheEndAfterTheFlag() {
    assertEquals("//set stone -pev 12", run("//set stone -pev", "//set stone -pev ", "//set stone -pev 1", "//set stone -pev 12"));
  }

  @Test
  void otherCommandOrNoFlag() {
    assertEquals("//walls st", PevFlag.withTail("//set stone -pev", "//walls st"));
    assertEquals("//set st", PevFlag.withTail("//set stone", "//set st"));
    assertEquals("//se", PevFlag.withTail("//set stone -pev", "//se"));
  }
}

module FetchPMU (
    input        clock,
    input        reset,
    input        trigFetch,
    input        trigIssue,
    input [31:0] pcChange
    // input        isIdle,
    // input [ 2:0] idleCause
);
  // Used for Delta(PC) statistics.
  import "DPI-C" function void
    notify_issue(int unsigned newPC);
  import "DPI-C" function void
    notify_fetch(int unsigned newPC);

  always_ff @(posedge clock) begin
    if (reset) begin
    end else begin
      if (trigFetch) notify_fetch(pcChange);
      if (trigIssue) notify_issue(pcChange);
      assert(!(trigFetch && trigIssue));
    end
  end

endmodule

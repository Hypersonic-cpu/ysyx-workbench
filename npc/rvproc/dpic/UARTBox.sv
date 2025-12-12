module UARTBox (
  input clock,
  input reset,
  input[7:0] bytein,
  input active
);
  always_ff @(posedge clock) begin : printConsole
    if (reset) begin end
    else begin
      if (active) begin
        $write("%c", bytein);
      end
    end
  end
endmodule

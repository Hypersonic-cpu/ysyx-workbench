module example(
  input reset,
  input clk,
  output reg [15:0] leds
);
  reg [31:0] count;
  always @(posedge clk) begin
    if (reset) begin leds <= 1; count <= 0; end
    else begin
      if (count == 0) leds <= {leds[14:0], leds[15]};
      count <= (count >= 5000000 ? 32'b0 : count + 1);
    end
  end
endmodule

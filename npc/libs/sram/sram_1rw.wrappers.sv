module sram_1rw_26x8 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [2:0] addr0,
  input  [25:0] din0,
  output [25:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(26),
    .NUM_WORDS(8)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_128x8 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [2:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(128),
    .NUM_WORDS(8)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_23x64 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [5:0] addr0,
  input  [22:0] din0,
  output [22:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(23),
    .NUM_WORDS(64)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_128x64 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [5:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(128),
    .NUM_WORDS(64)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_22x64 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [5:0] addr0,
  input  [21:0] din0,
  output [21:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(22),
    .NUM_WORDS(64)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_22x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [21:0] din0,
  output [21:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(22),
    .NUM_WORDS(128)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_32x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [31:0] din0,
  output [31:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(32),
    .NUM_WORDS(128)
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

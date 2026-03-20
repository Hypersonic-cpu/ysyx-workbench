module sram_1rw_17x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [16:0] din0,
  output [16:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(17),
    .NUM_WORDS(128)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

module sram_1rw_21x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [20:0] din0,
  output [20:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(21),
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

module sram_1rw_128x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
  sram_1rw #(
    .WORD_SIZE(128),
    .NUM_WORDS(128)
  ) u0 (
    .clk0(clk0), .csb0(csb0), .web0(web0),
    .addr0(addr0), .din0(din0), .dout0(dout0)
  );
endmodule

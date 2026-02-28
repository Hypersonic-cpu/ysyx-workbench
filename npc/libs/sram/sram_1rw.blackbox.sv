(* blackbox *)
module sram_1rw_22x64 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [5:0] addr0,
  input  [21:0] din0,
  output [21:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_25x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [24:0] din0,
  output [24:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_32x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [31:0] din0,
  output [31:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_128x64 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [5:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
endmodule

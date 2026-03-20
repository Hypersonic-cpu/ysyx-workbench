(* blackbox *)
module sram_1rw_17x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [16:0] din0,
  output [16:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_21x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [20:0] din0,
  output [20:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_32x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [31:0] din0,
  output [31:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_128x128 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [6:0] addr0,
  input  [127:0] din0,
  output [127:0] dout0
);
endmodule

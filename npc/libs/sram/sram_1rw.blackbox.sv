(* blackbox *)
module sram_1rw_24x16 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [3:0] addr0,
  input  [23:0] din0,
  output [23:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_256x16 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [3:0] addr0,
  input  [255:0] din0,
  output [255:0] dout0
);
endmodule

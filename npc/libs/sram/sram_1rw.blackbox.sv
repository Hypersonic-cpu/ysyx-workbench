(* blackbox *)
module sram_1rw_23x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [22:0] din0,
  output [22:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_256x32 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [4:0] addr0,
  input  [255:0] din0,
  output [255:0] dout0
);
endmodule

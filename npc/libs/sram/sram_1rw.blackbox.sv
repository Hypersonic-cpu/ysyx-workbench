(* blackbox *)
module sram_1rw_26x4 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [1:0] addr0,
  input  [25:0] din0,
  output [25:0] dout0
);
endmodule

(* blackbox *)
module sram_1rw_256x4 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [1:0] addr0,
  input  [255:0] din0,
  output [255:0] dout0
);
endmodule

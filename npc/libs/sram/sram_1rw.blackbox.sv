(* blackbox *)
module sram_1rw_25x4 (
  input              clk0,
  input              csb0,
  input              web0,
  input  [1:0] addr0,
  input  [24:0] din0,
  output [24:0] dout0
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

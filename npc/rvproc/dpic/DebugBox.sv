module DebugBox (
  input clock,
  input reset,
  output[4:0] probePin,
  input[31:0] probeOut,
  input[31:0] probePC
);
  // uint8_t max for PC
  // import "DPI-C" function void
  // ccdb_set_probe_reg(
  //   input byte unsigned regno);
  // import "DPI-C" function int unsigned
  // ccdb_read_reg(
  //   input byte unsigned regno);
  //
  // always_comb begin : ReadReg
  //   if (regno == 8'hff) begin
  //     ccdb_read_reg()
  //   end
  // end

endmodule

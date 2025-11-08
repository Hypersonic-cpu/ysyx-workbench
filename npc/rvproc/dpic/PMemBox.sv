module PMemBox(
  input clock,
  input reset,
  input [31:0] pcin,
  input [31:0] addr,
  input [31:0] data,
  input [7:0]  byteMask,
  input memEn,
  input wrEn,
  output[31:0] instRaw,
  output[31:0] loadRaw
  );

  import "DPI-C" function void
    pmem_init();
  import "DPI-C" function int unsigned
    pmem_read(input int unsigned raddr);
  import "DPI-C" function void
    pmem_write(
      input int unsigned waddr,
      input int unsigned wdata,
      input byte unsigned wmask);

  initial begin
    pmem_init();
  end

  reg [31:0] rdata;

  always_comb begin
    $display("MEn %d Wr %d Addr %x Mask %x", memEn, wrEn, addr, byteMask);
    if (memEn) begin
      rdata = 0;
      if (wrEn) begin
        pmem_write(addr, data, byteMask);
      end else begin
        rdata = pmem_read(addr);
      end
    end
    else begin
      rdata = 0;
    end
  end

  assign instRaw = pmem_read(pcin);
  assign loadRaw = rdata;
endmodule

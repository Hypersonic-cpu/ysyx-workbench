module CacheDPICBox (
    input               clock,
    input               reset,
    input        [15:0] id,
    input               valid,
    input               flush,
    input        [31:0] addr,
    output logic [31:0] resp,
    output logic [31:0] latency
);
endmodule

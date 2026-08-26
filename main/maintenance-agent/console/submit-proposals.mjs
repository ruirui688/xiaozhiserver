import { defineTool } from '@deepseek-ai/dsh-tools'

export const inject = ['tools']

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'submit_maintenance_proposals',
    description: 'Submit the final, reviewed maintenance proposals to the Xiaozhi Manager approval queue. Call exactly once after discussing and verifying the result with the operator.',
    parameters: {
      proposals: {
        type: 'array',
        required: true,
        items: {
          type: 'object',
          additionalProperties: true,
          properties: {
            actionType: { type: 'string', required: true },
            sourceUri: { type: 'string' },
            targetId: { type: 'string' },
            title: { type: 'string' },
            proposedText: { type: 'string' },
            rationale: { type: 'string', required: true },
            evidence: { type: 'array', items: { type: 'string' } }
          }
        }
      }
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }]
    },
    timeoutMs: 30000,
    async execute(args, exec) {
      const url = process.env.XIAOZHI_MAINTENANCE_CALLBACK_URL
      const secret = process.env.XIAOZHI_MAINTENANCE_CALLBACK_SECRET
      if (!url || !secret) throw new Error('Manager callback is not configured')
      const body = JSON.stringify({
        proposals: args.proposals,
        inputChars: Number(process.env.XIAOZHI_MAINTENANCE_INPUT_CHARS || 0),
        outputChars: 0
      })
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${secret}` },
        body,
        signal: exec.signal
      })
      if (!response.ok) throw new Error(`Manager rejected proposals (HTTP ${response.status})`)
      return '提案已安全提交到管理后台。最终写入仍需在“审核提案”中批准。'
    }
  }))
}

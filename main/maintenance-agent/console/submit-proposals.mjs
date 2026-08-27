import { defineTool } from '@deepseek-ai/dsh-tools'

export const inject = ['tools']

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'submit_maintenance_proposals',
    description: 'Submit the final reviewed maintenance proposals to the Xiaozhi Manager approval queue. Call exactly once, only after the operator explicitly asks to submit them.',
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
      const runToken = process.env.XIAOZHI_MAINTENANCE_CALLBACK_RUN_TOKEN
      if (!url || !secret || !runToken) throw new Error('Manager callback is not configured')
      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${secret}`,
          'X-Xiaozhi-Maintenance-Run-Token': runToken
        },
        body: JSON.stringify({
          proposals: args.proposals,
          inputChars: Number(process.env.XIAOZHI_MAINTENANCE_INPUT_CHARS || 0),
          outputChars: 0
        }),
        signal: exec.signal
      })
      if (!response.ok) throw new Error(`Manager rejected proposals (HTTP ${response.status})`)
      return '提案已提交到管理后台审批队列。只有在管理台审核批准后才会写入业务数据。'
    }
  }))
}

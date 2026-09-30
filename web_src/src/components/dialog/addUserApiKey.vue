<template>
  <div id="addUserApiKey" v-loading="isLoading">
    <el-dialog
      title="添加ApiKey"
      width="40%"
      top="2rem"
      :close-on-click-modal="false"
      :visible.sync="showDialog"
      :destroy-on-close="true"
      @close="close()"
    >
      <div id="shared" style="margin-right: 20px;">
        <el-form ref="formRef" :model="form" :rules="rules" status-icon label-width="80px">
          <el-form-item label="应用名" prop="app">
            <el-input
              v-model="form.app"
              property="app"
              autocomplete="off"/>
          </el-form-item>
          <el-form-item label="启用状态" prop="enable" style="text-align: left">
            <el-switch
              v-model="form.enable"
              property="enable"
              active-text="启用"
              inactive-text="停用"/>
          </el-form-item>
          <el-form-item label="过期时间" prop="expiresAt" style="text-align: left">
            <el-date-picker v-model="form.expiresAt"
                            style="width: 100%"
                            property="expiresAt"
                            type="datetime"
                            value-format="yyyy-MM-dd HH:mm:ss"
                            format="yyyy-MM-dd HH:mm:ss"
                            placeholder="选择过期时间"/>
          </el-form-item>
          <el-form-item label="备注信息" prop="remark">
            <el-input v-model="form.remark"
                      type="textarea"
                      property="remark"
                      autocomplete="off"
                      :autosize="{ minRows: 5}"
                      maxlength="255"
                      show-word-limit/>
          </el-form-item>
          <el-form-item>
            <div style="float: right;">
              <el-button type="primary" @click="onSubmit">保存</el-button>
              <el-button @click="close">取消</el-button>
            </div>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>
  </div>
</template>

<script>

export default {
  name: 'addUserApiKey',
  props: {},
  computed: {},
  created() {
  },
  data() {
    return {
      userId: null,
      form: {
        app: null,
        enable: true,
        expiresAt: this.defaultExpiresAt(),
        remark: null
      },
      rules: {
        app: [{required: true, trigger: 'blur', message: '应用名不能为空'}]
      },
      listChangeCallback: null,
      showDialog: false,
      isLoading: false
    };
  },
  methods: {
    defaultExpiresAt() {
      const date = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
      const pad = value => String(value).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    },
    resetForm() {
      this.form = {
        app: null,
        enable: true,
        expiresAt: this.defaultExpiresAt(),
        remark: null
      }
    },
    openDialog(userId, callback) {
      this.resetForm()
      this.userId = userId
      this.listChangeCallback = callback
      this.showDialog = true
    },
    onSubmit() {
      this.$refs.formRef.validate((valid) => {
        if (valid) {
          this.$axios({
            method: 'post',
            url: '/api/userApiKey/add',
            params: {
              userId: this.userId,
              app: this.form.app,
              enable: this.form.enable,
              expiresAt: this.form.expiresAt,
              remark: this.form.remark,
            }
          }).then((res) => {
            if (res.data.code === 0) {
              this.$message({
                showClose: true,
                message: 'ApiKey已生成，请立即保存：' + res.data.data,
                type: 'success'
              });
              this.showDialog = false
              if (this.listChangeCallback) {
                this.listChangeCallback()
              }
            } else {
              this.$message({
                showClose: true,
                message: res.data.msg,
                type: 'error'
              });
            }
          }).catch((error) => {
            console.error(error)
          });
        }
      });
    },
    close() {
      this.showDialog = false
    }
  },
};
</script>
